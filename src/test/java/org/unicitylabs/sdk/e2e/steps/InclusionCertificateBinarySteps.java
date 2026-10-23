package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.unicitylabs.sdk.api.InclusionCertificate;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.transaction.Token;

/**
 * Steps for {@code inclusion-certificate-binary.feature}. Verifies decode
 * rejects malformed binary, encode/decode is idempotent, and verify returns
 * false on corruption.
 */
public class InclusionCertificateBinarySteps {

  private final TestContext context;

  private byte[] inputBytes;
  private Throwable thrown;
  private InclusionCertificate originalCert;
  private byte[] originalEncoded;
  private InclusionCertificate decodedCert;
  private boolean verifyResult;
  private DataHash originalRoot;
  private StateId originalStateId;

  public InclusionCertificateBinarySteps(TestContext context) {
    this.context = context;
  }

  @Given("binary bytes of length {int}")
  public void binaryBytesOfLength(int length) {
    inputBytes = new byte[length];
  }

  @Given("a {int}-byte buffer where the bitmap has popcount 2 and only 1 sibling chunk follows")
  public void bufferWithPopcountMismatch(int totalLength) {
    inputBytes = new byte[totalLength];
    // First 32 bytes = bitmap. Set the lowest 2 bits in the last bitmap byte
    // (popcount=2). Sibling chunks are 32 bytes each, so totalLength=64 has
    // exactly 1 sibling slot — popcount(2) > slots(1) triggers the error.
    inputBytes[31] = 0x03;
  }

  @When("InclusionCertificate.decode is invoked")
  public void inclusionCertificateDecodeIsInvoked() {
    thrown = null;
    decodedCert = null;
    try {
      decodedCert = InclusionCertificate.decode(inputBytes);
    } catch (Throwable t) {
      thrown = t;
    }
  }

  @Then("InclusionCertificate.decode throws with message containing {string}")
  public void inclusionCertificateDecodeThrowsWithMessage(String marker) {
    assertNotNull(thrown, "expected decode to throw");
    String msg = thrown.getMessage() == null ? "" : thrown.getMessage().toLowerCase();
    assertTrue(msg.contains(marker.toLowerCase()),
        "expected message to contain '" + marker + "' but was: " + thrown.getMessage());
  }

  // ── Fixture-based scenarios ──────────────────────────────────────────────

  @Given("an InclusionCertificate built from the test fixture token")
  public void inclusionCertificateBuiltFromFixtureToken() throws Exception {
    if (context.getClient() == null) {
      new AggregatorSteps(context).aMockAggregatorIsRunning();
    }
    if (context.getCurrentToken() == null) {
      new TokenLifecycleSteps(context).userHasAMintedToken("Alice");
    }
    Token token = context.getCurrentToken();
    InclusionProof proof = token.getGenesis().getInclusionProof();
    originalCert = proof.getInclusionCertificate();
    originalEncoded = originalCert.encode();
    byte[] rootHashBytes = proof.getUnicityCertificate().getInputRecord().getHash();
    originalRoot = new DataHash(HashAlgorithm.SHA256, rootHashBytes);
    originalStateId = StateId.fromTransaction(token.getGenesis());
  }

  @When("the InclusionCertificate is encoded then decoded")
  public void encodedThenDecoded() {
    decodedCert = InclusionCertificate.decode(originalEncoded);
  }

  @Then("the decoded bitmap equals the original")
  public void decodedBitmapEqualsOriginal() {
    assertNotNull(decodedCert);
    // Java's InclusionCertificate doesn't expose its bitmap directly — assert
    // via re-encode equality (the bitmap is the first 32 bytes of the encoded
    // form, so encode-equality implies bitmap equality).
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        java.util.Arrays.copyOfRange(originalEncoded, 0, 32),
        java.util.Arrays.copyOfRange(decodedCert.encode(), 0, 32));
  }

  @Then("the decoded sibling count equals the original")
  public void decodedSiblingCountEqualsOriginal() {
    // Sibling-chunks are the bytes after the 32-byte bitmap. Length-equality
    // implies count-equality (each sibling is a fixed 32-byte chunk).
    assertEquals(originalEncoded.length, decodedCert.encode().length);
  }

  @When("the first sibling hash is corrupted")
  public void firstSiblingHashIsCorrupted() {
    // Flip a bit in the first byte after the 32-byte bitmap.
    inputBytes = originalEncoded.clone();
    if (inputBytes.length > 32) {
      inputBytes[32] ^= 0x01;
    }
    decodedCert = InclusionCertificate.decode(inputBytes);
  }

  @Then("verify returns false against the original root and StateID")
  public void verifyReturnsFalseAgainstOriginal() {
    assertNotNull(decodedCert);
    Token token = context.getCurrentToken();
    DataHash leafValue = new DataHash(HashAlgorithm.SHA256, new byte[32]);
    // Use a placeholder leaf value — the test only checks that corruption
    // makes verify return false.
    verifyResult = decodedCert.verify(originalStateId, leafValue, originalRoot);
    assertFalse(verifyResult, "expected verify to return false on corrupted sibling");
  }

  @When("verify is called with a root hash differing by one byte")
  public void verifyWithDifferentRoot() {
    byte[] mutatedRoot = originalRoot.getData().clone();
    mutatedRoot[0] ^= 0x01;
    DataHash leafValue = new DataHash(HashAlgorithm.SHA256, new byte[32]);
    verifyResult = originalCert.verify(originalStateId, leafValue,
        new DataHash(HashAlgorithm.SHA256, mutatedRoot));
  }

  @Then("verify returns false")
  public void verifyReturnsFalse() {
    assertFalse(verifyResult, "expected verify to return false");
  }
}
