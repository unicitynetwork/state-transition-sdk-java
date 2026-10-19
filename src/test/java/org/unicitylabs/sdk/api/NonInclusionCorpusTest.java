package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerificationResult;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealHashMatchesWithRootHashRule;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealQuorumSignaturesVerificationRule;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.UnsignedLongs;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Runs the shared non-inclusion v2 corpus: every positive case, every negative (rejection class,
 * no snapshot) and the ordered sequences on one verifier.
 */
class NonInclusionCorpusTest {

  private static final Path ROOT = Paths.get("src/test/resources/shared/non-inclusion/v2");
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Strict certificate verification of the corpus authority: network, path to the seal hash, exact
   * epoch, not before the epoch start, quorum. A stand-in for the operation verifier of the
   * shared verification package, so the corpus pins the NI logic by itself.
   */
  private static final class StrictTestVerifier extends UnicityCertificateVerifier {
    @Override
    public UnicityCertificateVerificationResult verifyCertificate(RootTrustBase trustBase,
        UnicityCertificate uc) {
      List<VerificationResult<?>> results = new ArrayList<>();
      if (!uc.getUnicitySeal().getNetworkId().equals(trustBase.getNetworkId())) {
        results.add(new VerificationResult<>("Network", VerificationStatus.FAIL));
        return UnicityCertificateVerificationResult.fail(results);
      }
      VerificationResult<VerificationStatus> hash =
          UnicitySealHashMatchesWithRootHashRule.verify(uc);
      results.add(hash);
      if (hash.getStatus() != VerificationStatus.OK) {
        return UnicityCertificateVerificationResult.fail(results);
      }
      if (uc.getUnicitySeal().getEpoch() != trustBase.getEpoch()
          || UnsignedLongs.lessThan(uc.getUnicitySeal().getRootChainRoundNumber(),
              trustBase.getEpochStartRound())) {
        results.add(new VerificationResult<>("Epoch", VerificationStatus.FAIL));
        return UnicityCertificateVerificationResult.fail(results);
      }
      VerificationResult<VerificationStatus> quorum =
          UnicitySealQuorumSignaturesVerificationRule.verify(trustBase, uc.getUnicitySeal());
      results.add(quorum);
      return quorum.getStatus() == VerificationStatus.OK
          ? UnicityCertificateVerificationResult.ok(results)
          : UnicityCertificateVerificationResult.fail(results);
    }

    @Override
    public UnicityCertificateVerifier beginOperation(VerificationPolicy policy,
        PartitionViewPolicy pins) {
      return this;
    }
  }

  private static JsonNode read(String relative) throws IOException {
    return JSON.readTree(ROOT.resolve(relative).toFile());
  }

  private static ShardId shard(String bits) {
    int total = bits.length() + 1;
    byte[] bytes = new byte[(total + 7) / 8];
    for (int i = 0; i < bits.length(); i++) {
      if (bits.charAt(i) == '1') {
        bytes[i / 8] |= (byte) (0x80 >> (i % 8));
      }
    }
    bytes[bits.length() / 8] |= (byte) (0x80 >> (bits.length() % 8));
    return ShardId.decode(bytes);
  }

  private static String bits(ShardId shard) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < shard.getLength(); i++) {
      sb.append(shard.getBit(i));
    }
    return sb.toString();
  }

  private static StateId stateId(String hex) {
    return StateId.fromCbor(CborSerializer.encodeByteString(HexConverter.decode(hex)));
  }

  private static final class Setup {
    final RootTrustBase trustBase;
    final NonInclusionContext context;

    Setup(JsonNode ctx) throws IOException {
      String json = new String(
          Files.readAllBytes(ROOT.resolve(ctx.get("trustBaseJson").asText())),
          StandardCharsets.UTF_8);
      this.trustBase = RootTrustBase.fromJson(json);
      NonInclusionContext c = NonInclusionContext.unsharded()
          .withShard(shard(ctx.get("shard").asText()));
      if (!"0".equals(ctx.get("nextEpochStart").asText())) {
        c = c.withAuthorityEndRound(UnsignedLongs.parseDecimal(ctx.get("nextEpochStart").asText()));
      }
      String pin = ctx.get("shardConfHash").asText();
      if (!pin.isEmpty()) {
        c = c.withShardConfigurationHash(HexConverter.decode(pin));
      }
      this.context = c;
    }
  }

  private static void check(JsonNode vector, UnicityCertificateVerifier verifier, String label)
      throws IOException {
    Setup setup = new Setup(read("contexts.json").get(vector.get("context").asText()));
    StateId query = stateId(vector.get("query").asText());
    NonInclusionProof proof = new NonInclusionProof(query,
        HexConverter.decode(vector.get("wrapper").asText()));
    expect(vector.get("expected"), label, () ->
        NonInclusionVerifier.verify(verifier, setup.trustBase, proof, setup.context));
  }

  private interface Run {
    NonInclusionOutcome run();
  }

  private static void expect(JsonNode expected, String label, Run run) {
    String outcome = expected.get("outcome").asText();
    if (!"rejected".equals(outcome)) {
      NonInclusionOutcome got;
      try {
        got = run.run();
      } catch (RuntimeException e) {
        throw new AssertionError(label + ": expected " + outcome + " but " + e, e);
      }
      assertEquals(outcome.toUpperCase(Locale.ROOT), got.getKind().name(), label);
      assertEquals(expected.get("stateId").asText(),
          HexConverter.encode(got.getStateId().getData()), label);
      assertSnapshot(expected.get("snapshot"), got.getSnapshot(), label);
      return;
    }
    List<String> classes = new ArrayList<>();
    classes.add(expected.get("rejection").asText());
    if (expected.has("acceptable")) {
      for (JsonNode a : expected.get("acceptable")) {
        classes.add(a.asText());
      }
    }
    try {
      NonInclusionOutcome got = run.run();
      fail(label + ": expected a rejection " + classes + " but got " + got);
    } catch (NonInclusionVerificationException e) {
      assertTrue(classes.contains(e.getReason().name().toLowerCase(Locale.ROOT)),
          label + ": rejected as " + e.getReason() + ", expected one of " + classes);
    }
  }

  private static void assertSnapshot(JsonNode want, NonInclusionSnapshot got, String label) {
    assertEquals(want.get("networkId").asInt(), got.getNetworkId().getId(), label);
    assertEquals(want.get("partitionId").asLong(), got.getPartitionId(), label);
    assertEquals(want.get("shardId").asText(), bits(got.getShardId()), label);
    assertEquals(want.get("rootRound").asText(), got.getRootRoundDecimal(), label);
    assertEquals(want.get("sealTimestampSeconds").asText(), got.getSealTimestampSecondsDecimal(),
        label);
    assertEquals(want.get("rootEpoch").asText(), got.getRootEpochDecimal(), label);
    assertEquals(want.get("shardRound").asText(), got.getShardRoundDecimal(), label);
    assertEquals(want.get("shardEpoch").asText(), got.getShardEpochDecimal(), label);
    JsonNode root = want.get("stateRoot");
    if (root == null || root.isNull()) {
      assertNull(got.getStateRoot(), label);
    } else {
      assertEquals(root.asText(), HexConverter.encode(got.getStateRoot()), label);
    }
  }

  private static List<Path> files(String dir) throws IOException {
    try (Stream<Path> s = Files.list(ROOT.resolve(dir))) {
      return s.filter(p -> p.toString().endsWith(".json")).sorted().collect(Collectors.toList());
    }
  }

  @Test
  void everyPositiveCase() throws Exception {
    List<Path> cases = files("cases");
    assertTrue(cases.size() > 20);
    List<String> failures = new ArrayList<>();
    for (Path file : cases) {
      try {
        check(JSON.readTree(file.toFile()), new StrictTestVerifier(),
            "cases/" + file.getFileName());
      } catch (AssertionError | RuntimeException e) {
        failures.add(String.valueOf(e.getMessage()));
      }
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @Test
  void everyNegativeIsRejectedWithoutSnapshotInAnAcceptedClass() throws Exception {
    List<String> failures = new ArrayList<>();
    for (Path file : files("negative")) {
      try {
        check(JSON.readTree(file.toFile()), new StrictTestVerifier(),
            "negative/" + file.getFileName());
      } catch (AssertionError | RuntimeException e) {
        failures.add(String.valueOf(e.getMessage()));
      }
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @Test
  void sequencesOnOneVerifier() throws Exception {
    JsonNode sequences = read("sequences.json").get("sequences");
    assertTrue(sequences.size() > 0);
    for (JsonNode sequence : sequences) {
      UnicityCertificateVerifier verifier = new StrictTestVerifier();
      for (JsonNode step : sequence.get("steps")) {
        String vectorName = step.get("vector").asText();
        JsonNode vector = read(vectorName + ".json");
        Setup setup = new Setup(read("contexts.json").get(sequence.get("context").asText()));
        StateId query = stateId(vector.get("query").asText());
        NonInclusionProof proof = new NonInclusionProof(query,
            HexConverter.decode(vector.get("wrapper").asText()));
        expect(step.get("expected"), sequence.get("name").asText() + ":" + vectorName, () ->
            NonInclusionVerifier.verify(verifier, setup.trustBase, proof, setup.context));
      }
    }
  }

  @Test
  void aProofBoundToAnotherStateIsRejectedBeforeAnyWork() throws Exception {
    JsonNode vector = read("cases/singleton.json");
    Setup setup = new Setup(read("contexts.json").get(vector.get("context").asText()));
    NonInclusionProof proof = new NonInclusionProof(stateId(vector.get("query").asText()),
        HexConverter.decode(vector.get("wrapper").asText()));
    byte[] other = stateId(vector.get("query").asText()).getData();
    other[31] ^= 1;
    try {
      NonInclusionVerifier.verify(new StrictTestVerifier(), setup.trustBase, proof,
          StateId.fromCbor(CborSerializer.encodeByteString(other)), setup.context);
      fail("expected a mismatch");
    } catch (NonInclusionVerificationException e) {
      assertEquals(NonInclusionVerificationException.Reason.TARGET_MISMATCH, e.getReason());
    }
    assertNotNull(proof);
  }

  @Test
  void thePlainLegacyVerifierIsRefused() throws Exception {
    JsonNode vector = read("cases/singleton.json");
    Setup setup = new Setup(read("contexts.json").get(vector.get("context").asText()));
    NonInclusionProof proof = new NonInclusionProof(stateId(vector.get("query").asText()),
        HexConverter.decode(vector.get("wrapper").asText()));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
        NonInclusionVerifier.verify(new UnicityCertificateVerifier(), setup.trustBase, proof,
            setup.context));
  }
}
