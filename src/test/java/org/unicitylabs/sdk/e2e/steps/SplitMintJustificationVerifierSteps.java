package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.functional.payment.TestPaymentData;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.CertifiedMintTransaction;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Steps for {@code split-mint-justification-verifier.feature}: build a real
 * split-mint via the aggregator, then surgically mutate the resulting
 * {@link CertifiedMintTransaction}'s data/justification fields to reach each
 * field-level FAIL branch in {@link SplitMintJustificationVerifier#verify}.
 *
 * <p>Mutation strategy: round-trip the cert through CBOR ({@code [mintTxCbor,
 * inclProofCbor]}), modify the inner MintTransaction's CBOR slot for data or
 * justification, then re-encode and decode back via
 * {@link CertifiedMintTransaction#fromCbor}. This mirrors the TS suite's
 * {@code mockCert} approach without relying on Java reflection.
 */
public class SplitMintJustificationVerifierSteps {

  private final TestContext context;

  private CertifiedMintTransaction baseCert;
  private CertifiedMintTransaction mutatedCert;
  private VerificationResult<VerificationStatus> verifyResult;

  // 32-byte AssetIds — TS-style. Distinct from the SplitSteps "ASSET_1"/"ASSET_2"
  // shorthand to keep mutation-test fixtures isolated.
  private static final Asset ASSET_A = new Asset(
      new AssetId(padTo32("SMJV_ASSET_A")),
      BigInteger.valueOf(100));
  private static final Asset ASSET_B = new Asset(
      new AssetId(padTo32("SMJV_ASSET_B")),
      BigInteger.valueOf(200));

  public SplitMintJustificationVerifierSteps(TestContext context) {
    this.context = context;
  }

  /**
   * Lazily resolves Alice's first split-child's CertifiedMintTransaction.
   * Background step is shared with {@link SplitMintJustificationEnvelopeSteps}
   * which populates {@code context.getSplitChildren()}.
   */
  private void ensureBaseCert() {
    if (baseCert != null) {
      return;
    }
    assertNotNull(context.getSplitChildren(), "no split children recorded");
    assertTrue(!context.getSplitChildren().isEmpty(),
        "expected at least one split child — Background must run first");
    baseCert = context.getSplitChildren().get(0).getGenesis();
  }

  @Given("a CertifiedMintTransaction is mutated by stripping the justification field")
  public void mutateByStrippingJustification() {
    ensureBaseCert();
    mutatedCert = rewriteCertCbor(baseCert, true, null, false, null);
  }

  @Given("a CertifiedMintTransaction is mutated by stripping the data field")
  public void mutateByStrippingData() {
    ensureBaseCert();
    mutatedCert = rewriteCertCbor(baseCert, false, null, true, null);
  }

  @Given("a CertifiedMintTransaction is mutated by adding an extra asset to data not present in proofs")
  public void mutateByAddingExtraAssetToData() {
    ensureBaseCert();
    Set<Asset> tampered = new HashSet<>(currentAssets(baseCert));
    Asset extra = new Asset(
        new AssetId(padTo32("SMJV_EXTRA_ASSET")),
        BigInteger.ONE);
    tampered.add(extra);
    mutatedCert = withDataPayload(baseCert, new TestPaymentData(tampered).encode());
  }

  @Given("a CertifiedMintTransaction is mutated by renaming one proof's assetId to one not in data")
  public void mutateByRenamingAssetId() {
    ensureBaseCert();
    // The verifier checks aggregation/asset paths *before* the data lookup, so
    // renaming the assetId on the proof side trips path verification first. To
    // exercise the "Asset id ... not found in asset data" branch we instead
    // rename one asset id on the *data* side: proofs stay valid, but their
    // assetIds no longer appear in payment data.
    Set<Asset> renamed = new HashSet<>();
    boolean first = true;
    for (Asset a : currentAssets(baseCert)) {
      if (first) {
        renamed.add(new Asset(
            new AssetId(padTo32("SMJV_RENAMED_ASSET")),
            a.getValue()));
        first = false;
      } else {
        renamed.add(a);
      }
    }
    mutatedCert = withDataPayload(baseCert, new TestPaymentData(renamed).encode());
  }

  @Given("a CertifiedMintTransaction is mutated by mismatching one asset's value between data and tree")
  public void mutateByMismatchingAssetValue() {
    ensureBaseCert();
    Set<Asset> bumped = new HashSet<>();
    boolean first = true;
    for (Asset a : currentAssets(baseCert)) {
      if (first) {
        bumped.add(new Asset(a.getId(), a.getValue().add(BigInteger.ONE)));
        first = false;
      } else {
        bumped.add(a);
      }
    }
    mutatedCert = withDataPayload(baseCert, new TestPaymentData(bumped).encode());
  }

  @When("SplitMintJustificationVerifier.verify is invoked")
  public void splitMintJustificationVerifierVerifyIsInvoked() {
    assertNotNull(mutatedCert, "no mutated cert — Given step skipped?");
    SplitMintJustificationVerifier verifier = new SplitMintJustificationVerifier(
        context.getTrustBase(),
        context.getPredicateVerifier(),
        TestPaymentData::decode);
    verifyResult = verifier.verify(mutatedCert, context.getMintJustificationVerifier());
  }

  @Then("the SplitMintJustificationVerifier verification result is FAIL")
  public void verificationResultIsFail() {
    assertNotNull(verifyResult, "no verifier result — When step skipped?");
    assertEquals(
        VerificationStatus.FAIL,
        verifyResult.getStatus(),
        "expected FAIL, got " + verifyResult.getStatus() + ": " + verifyResult.getMessage());
  }

  @Then("the SplitMintJustificationVerifier failure message contains {string}")
  public void failureMessageContains(String fragment) {
    assertNotNull(verifyResult, "no verifier result");
    String haystack = verifyResult.getMessage() == null
        ? "" : verifyResult.getMessage().toLowerCase();
    assertTrue(haystack.contains(fragment.toLowerCase()),
        "expected '" + fragment + "' in: " + verifyResult.getMessage());
  }

  // ── Mutation helpers ────────────────────────────────────────────────────

  private Set<Asset> currentAssets(CertifiedMintTransaction cert) {
    byte[] dataBytes = cert.getData().orElse(null);
    assertNotNull(dataBytes, "cert has no payload data");
    return TestPaymentData.decode(dataBytes).getAssets();
  }

  /** Replace just the data payload, keeping original justification. */
  private CertifiedMintTransaction withDataPayload(
      CertifiedMintTransaction cert, byte[] dataBytes) {
    return rewriteCertCbor(cert, false, null, false, dataBytes);
  }

  /**
   * Surgical CBOR rewrite of CertifiedMintTransaction:
   * - Top-level CBOR is a 2-element array: [mintTxCbor, inclProofCbor].
   * - mintTxCbor is tagged ({@link MintTransaction#CBOR_TAG}, [version, recipient,
   *   tokenId, tokenType, justification?, data?]).
   * - We decode, swap slot 4 (justification) and/or slot 5 (data), re-encode,
   *   and decode back via {@link CertifiedMintTransaction#fromCbor}.
   */
  private CertifiedMintTransaction rewriteCertCbor(
      CertifiedMintTransaction cert,
      boolean useNullJust,
      byte[] justification,
      boolean useNullData,
      byte[] overrideDataBytes) {
    byte[] certCbor = cert.toCbor();
    List<byte[]> outer = CborDeserializer.decodeArray(certCbor, 2);
    byte[] mintTxCbor = outer.get(0);
    byte[] inclProofCbor = outer.get(1);

    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(mintTxCbor);
    List<byte[]> mintFields = CborDeserializer.decodeArray(tag.getData(), 6);

    if (useNullJust) {
      mintFields.set(4, CborSerializer.encodeNull());
    } else if (justification != null) {
      mintFields.set(4, CborSerializer.encodeByteString(justification));
    }
    if (useNullData) {
      mintFields.set(5, CborSerializer.encodeNull());
    } else if (overrideDataBytes != null) {
      mintFields.set(5, CborSerializer.encodeByteString(overrideDataBytes));
    }

    byte[] newMintTxCbor = CborSerializer.encodeTag(
        tag.getTag(),
        CborSerializer.encodeArray(mintFields.toArray(new byte[0][])));
    byte[] newCertCbor = CborSerializer.encodeArray(newMintTxCbor, inclProofCbor);
    return CertifiedMintTransaction.fromCbor(newCertCbor);
  }

  private static byte[] padTo32(String label) {
    byte[] base = label.getBytes(StandardCharsets.UTF_8);
    return Arrays.copyOf(base, 32);
  }
}
