package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.List;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.InclusionCertificate;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationRule;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationStatus;
import org.unicitylabs.sdk.util.verification.VerificationResult;

/**
 * Steps for {@code inclusion-proof-statuses.feature}: mutate the InclusionProof
 * in different ways and assert the verification rule emits the right status.
 *
 * <p>Mutation strategy: round-trip the proof's CBOR through manual element-
 * level rewriting, then run {@link InclusionProofVerificationRule#verify}
 * directly (bypassing the higher-level {@code Token.verify} which would also
 * gate on predicate verification).
 *
 * <p>Sibling-corruption nuance: the hermetic test aggregator builds an SMT
 * with one leaf per submitted certification. Alice's mint alone produces a
 * 0-sibling proof, which makes byte-level sibling corruption a no-op. To
 * reach the {@code PATH_INVALID} branch we seed a second dummy leaf and
 * re-fetch Alice's proof (the test aggregator returns a fresh proof against
 * the current SMT root for the same StateId).
 */
public class InclusionProofStatusesSteps {

  private final TestContext context;
  private InclusionProof mutated;
  private boolean siblingSeeded;

  public InclusionProofStatusesSteps(TestContext context) {
    this.context = context;
  }

  @When("the inclusion proof has its inclusionCertificate removed")
  public void inclusionCertificateRemoved() {
    mutated = mutateProof(currentProof(), true, false, false);
  }

  @When("the inclusion proof has its certificationData removed")
  public void certificationDataRemoved() {
    mutated = mutateProof(currentProof(), false, true, false);
  }

  @When("the inclusion proof's first sibling hash is corrupted")
  public void firstSiblingHashCorrupted() {
    seedSiblingLeafOnce();
    mutated = mutateProof(currentProof(), false, false, true);
  }

  @When("the inclusion proof's transactionHash is replaced with garbage")
  public void transactionHashReplacedWithGarbage() {
    InclusionProof original = currentProof();
    // CertificationData CBOR layout: tag(39031, [version, lockScript, sourceStateHash,
    // transactionHash, unlockScript]). Element index 3 = transaction hash byte string.
    byte[] certCbor = original.getCertificationData().get().toCbor();
    CborDeserializer.CborTag certTag = CborDeserializer.decodeTag(certCbor);
    List<byte[]> certElements = CborDeserializer.decodeArray(certTag.getData(), 5);
    byte[] originalHash = CborDeserializer.decodeByteString(certElements.get(3));
    byte[] garbageHash = new byte[32];
    java.util.Arrays.fill(garbageHash, (byte) 0xee);
    System.arraycopy(garbageHash, 0, originalHash, originalHash.length - 32, 32);
    certElements.set(3, CborSerializer.encodeByteString(originalHash));
    byte[] newCertCbor = CborSerializer.encodeTag(certTag.getTag(),
        CborSerializer.encodeArray(certElements.toArray(new byte[0][])));
    mutated = rebuildProofWithCertCbor(original, newCertCbor);
  }

  @When("the inclusion proof's first sibling hash is corrupted on top")
  public void firstSiblingHashCorruptedOnTop() {
    // Order matters: txhash mutation has been applied to `mutated` already.
    // We need a sibling to corrupt — seed one, then refetch Alice's fresh
    // proof (now with siblings), re-apply the txhash mutation, and corrupt
    // the sibling on top.
    seedSiblingLeafOnce();
    InclusionProof freshOriginal = currentProof();
    // Re-apply the txhash mutation on the now-sibling-bearing fresh proof.
    transactionHashReplacedWithGarbageFrom(freshOriginal);
    mutated = corruptSiblingOf(mutated);
  }

  @When("the inclusion proof is mutated by {string}")
  public void inclusionProofIsMutatedBy(String mutation) {
    switch (mutation) {
      case "drop-inclusion-certificate":
        mutated = mutateProof(currentProof(), true, false, false);
        break;
      case "drop-certification-data":
        mutated = mutateProof(currentProof(), false, true, false);
        break;
      case "corrupt-sibling":
        seedSiblingLeafOnce();
        mutated = mutateProof(currentProof(), false, false, true);
        break;
      case "corrupt-txhash":
        transactionHashReplacedWithGarbage();
        break;
      default:
        throw new IllegalArgumentException("Unknown mutation: " + mutation);
    }
  }

  @Then("verification of the modified proof returns {string}")
  public void verificationOfModifiedProofReturns(String expectedStatus) {
    assertNotNull(mutated, "no mutated proof");
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token");
    VerificationResult<InclusionProofVerificationStatus> result =
        InclusionProofVerificationRule.verify(
            context.getTrustBase(), context.getPredicateVerifier(),
            mutated, token.getGenesis());
    assertEquals(expectedStatus, result.getStatus().name(),
        "rule message: " + result.getMessage());
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  /**
   * Returns the inclusion proof currently associated with Alice's token.
   * If a sibling has been seeded into the SMT after Alice minted, the proof
   * embedded on the Token object is stale (0 siblings); we therefore go
   * through the aggregator once seeded to get a fresh, sibling-bearing
   * proof against the current SMT root.
   */
  private InclusionProof currentProof() {
    Token token = context.getCurrentToken();
    if (siblingSeeded) {
      try {
        StateId stateId = StateId.fromTransaction(token.getGenesis());
        InclusionProofResponse resp = context.getClient().getInclusionProof(stateId).get();
        return resp.getInclusionProof();
      } catch (Exception e) {
        throw new RuntimeException("failed to refetch Alice's proof", e);
      }
    }
    return token.getGenesis().getInclusionProof();
  }

  /**
   * Submits a synthetic mint certification through the aggregator so the
   * SMT has at least 2 leaves. Idempotent across the scenario.
   */
  private void seedSiblingLeafOnce() {
    if (siblingSeeded) {
      return;
    }
    try {
      SigningService dummySigning = SigningService.generate();
      SignaturePredicate dummyPredicate =
          SignaturePredicate.fromSigningService(dummySigning);
      MintTransaction dummyMint = MintTransaction.create(
          dummyPredicate, TokenId.generate(), TokenType.generate(), null, null);
      CertificationData dummyCert = CertificationData.fromMintTransaction(dummyMint);
      context.getClient().submitCertificationRequest(dummyCert).get();
      siblingSeeded = true;
    } catch (Exception e) {
      throw new RuntimeException("failed to seed sibling leaf", e);
    }
  }

  private void transactionHashReplacedWithGarbageFrom(InclusionProof original) {
    byte[] certCbor = original.getCertificationData().get().toCbor();
    CborDeserializer.CborTag certTag = CborDeserializer.decodeTag(certCbor);
    List<byte[]> certElements = CborDeserializer.decodeArray(certTag.getData(), 5);
    byte[] originalHash = CborDeserializer.decodeByteString(certElements.get(3));
    byte[] garbageHash = new byte[32];
    java.util.Arrays.fill(garbageHash, (byte) 0xee);
    System.arraycopy(garbageHash, 0, originalHash, originalHash.length - 32, 32);
    certElements.set(3, CborSerializer.encodeByteString(originalHash));
    byte[] newCertCbor = CborSerializer.encodeTag(certTag.getTag(),
        CborSerializer.encodeArray(certElements.toArray(new byte[0][])));
    mutated = rebuildProofWithCertCbor(original, newCertCbor);
  }

  /** Rebuilds an InclusionProof, optionally nulling certCert/certData and/or corrupting a sibling. */
  private InclusionProof mutateProof(InclusionProof original,
      boolean dropInclCert, boolean dropCertData, boolean corruptSib) {
    byte[] origCbor = original.toCbor();
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(origCbor);
    List<byte[]> elements = CborDeserializer.decodeArray(tag.getData(), 4);
    // elements: [version, certData, inclCert, ucert]
    if (dropCertData) {
      elements.set(1, CborSerializer.encodeNull());
    }
    if (dropInclCert) {
      elements.set(2, CborSerializer.encodeNull());
    }
    if (corruptSib) {
      InclusionCertificate cert = original.getInclusionCertificate();
      byte[] inclEncoded = cert.encode().clone();
      if (inclEncoded.length <= 32) {
        throw new IllegalStateException(
            "cert has no siblings to corrupt — sibling-leaf seeding failed");
      }
      // Flip a bit in the first sibling slot — popcount unchanged (we corrupt
      // hash bytes after the bitmap, not the bitmap itself).
      inclEncoded[32] ^= (byte) 0xff;
      elements.set(2, CborSerializer.encodeByteString(inclEncoded));
    }
    byte[] newCbor = CborSerializer.encodeTag(tag.getTag(),
        CborSerializer.encodeArray(elements.toArray(new byte[0][])));
    return InclusionProof.fromCbor(newCbor);
  }

  private InclusionProof rebuildProofWithCertCbor(InclusionProof original, byte[] newCertCbor) {
    byte[] origCbor = original.toCbor();
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(origCbor);
    List<byte[]> elements = CborDeserializer.decodeArray(tag.getData(), 4);
    elements.set(1, newCertCbor);
    return InclusionProof.fromCbor(CborSerializer.encodeTag(tag.getTag(),
        CborSerializer.encodeArray(elements.toArray(new byte[0][]))));
  }

  private InclusionProof corruptSiblingOf(InclusionProof proof) {
    byte[] origCbor = proof.toCbor();
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(origCbor);
    List<byte[]> elements = CborDeserializer.decodeArray(tag.getData(), 4);
    byte[] inclEncoded = proof.getInclusionCertificate().encode().clone();
    if (inclEncoded.length <= 32) {
      throw new IllegalStateException(
          "cert has no siblings to corrupt — sibling-leaf seeding failed");
    }
    inclEncoded[32] ^= (byte) 0xff;
    elements.set(2, CborSerializer.encodeByteString(inclEncoded));
    return InclusionProof.fromCbor(CborSerializer.encodeTag(tag.getTag(),
        CborSerializer.encodeArray(elements.toArray(new byte[0][]))));
  }
}
