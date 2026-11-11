package org.unicitylabs.sdk.interop;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.unicitylabs.sdk.integration.AggregatorStack;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifierService;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Cross-implementation check: a token minted by the published TypeScript SDK, verified here.
 *
 * <p>The golden CertificationData vectors both SDKs carry cover what reaches the aggregator, not
 * Token or the certified transactions inside it — they stayed green while neither SDK could read
 * the other's tokens. Carrying a real token across the boundary is what covers those.
 *
 * <p>Minted against the aggregator this suite starts, by the npm artifact rather than the other
 * repo's source tree, so nothing is committed and no vector can go stale.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsSdkInteropIntegrationTest {

  /** npm install plus a mint and a transfer, both waiting on certification. */
  private static final long TIMEOUT_MINUTES = 5;
  private static final String NODE_IMAGE = "node:22-alpine";

  private AggregatorStack stack;
  private String tokenHex;
  private long expiresAt;

  @BeforeAll
  void mintWithTheTypeScriptSdk() throws Exception {
    this.stack = AggregatorStack.start();

    String interop = Paths.get("src", "test", "resources", "interop").toAbsolutePath().toString();
    // the trust base history the aggregator serves, as the file the TypeScript client opens
    Path bundle = Files.createTempFile("trust-base", ".cbor");
    Files.write(bundle, this.stack.fetchTrustBaseBundle());
    bundle.toFile().deleteOnExit();
    List<String> command = new ArrayList<>(List.of(
            "docker", "run", "--rm",
            // Join the stack's own network, so the aggregator is reachable by service name.
            "--network", this.stack.getNetworkName(),
            "-v", interop + ":/interop:ro",
            "-v", bundle.toAbsolutePath() + ":/trust-base.cbor:ro",
            "-e", "AGGREGATOR_URL=http://aggregator:3000",
            "-e", "TRUST_BASE_PATH=/trust-base.cbor",
            "-w", "/work",
            NODE_IMAGE,
            "sh", "-c",
            // The script runs from /work so node resolves the SDK against the node_modules
            // installed there; /interop is mounted read-only.
            "cp /interop/* /work/ && npm install --silent --no-audit --no-fund"
                    + " && node /work/mint-token.mjs"));

    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append('\n');
      }
    }
    if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new IllegalStateException("the TypeScript SDK did not finish minting in time");
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException(
              "minting with the TypeScript SDK failed:\n" + output);
    }

    this.tokenHex = value(output.toString(), "TOKEN_HEX=");
    this.expiresAt = Long.parseLong(value(output.toString(), "EXPIRES_AT="));
  }

  @AfterAll
  void stopStack() {
    if (this.stack != null) {
      this.stack.close();
    }
  }

  private static String value(String output, String prefix) {
    for (String line : output.split("\\R")) {
      if (line.startsWith(prefix)) {
        return line.substring(prefix.length()).trim();
      }
    }

    throw new AssertionError("the TypeScript SDK printed no " + prefix + "; output was:\n" + output);
  }

  @Test
  void verifiesATokenMintedByThePublishedTypeScriptSdk() throws Exception {
    Token token = Token.fromCbor(HexConverter.decode(this.tokenHex));

    VerificationContext context = new VerificationContext(
            this.stack.getTrustBase(),
            PredicateVerifierService.create(),
            new MintJustificationVerifierService(),
            new TokenIssuanceVerifierService(false));

    Assertions.assertEquals(VerificationStatus.OK, token.verify(context).getStatus(),
            "a token the published TypeScript SDK produced must verify here unchanged");

    // The deadline is committed by the transaction hash, so it has to survive the crossing.
    Assertions.assertEquals(this.expiresAt,
            token.getGenesis().getExpiresAt().orElseThrow(AssertionError::new));
    Assertions.assertEquals(1, token.getTransactions().size());
    Assertions.assertEquals(this.expiresAt,
            token.getTransactions().get(0).getExpiresAt().orElseThrow(AssertionError::new));
  }
}
