package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.HashSet;
import java.util.Set;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.functional.payment.TestPaymentData;
import org.unicitylabs.sdk.payment.SplitAssetProof;
import org.unicitylabs.sdk.payment.SplitMintJustification;
import org.unicitylabs.sdk.transaction.Token;

public class SplitMintJustificationEnvelopeSteps {

  private final TestContext context;
  private SplitMintJustification original;
  private byte[] originalCbor;
  private SplitMintJustification decoded;
  private Throwable thrown;

  public SplitMintJustificationEnvelopeSteps(TestContext context) {
    this.context = context;
  }

  @Given("Alice has split-minted 2 tokens with 2 payment assets")
  public void aliceHasSplitMinted2TokensWith2Assets() throws Exception {
    SplitSteps split = new SplitSteps(context);
    split.userHasAMintedTokenWith2PaymentAssets("Alice");
    split.userSplitsTheTokenInto2NewTokens("Alice");
  }

  @Given("the SplitMintJustification of one of Alice's split tokens")
  public void theSplitMintJustificationOfOneOfAlicesSplitTokens() {
    // After Alice's split, her child tokens are recorded in
    // TestContext.splitChildren with their SplitMintJustification in the
    // genesis transaction's justification field.
    assertNotNull(context.getSplitChildren(), "no split children recorded");
    assertTrue(!context.getSplitChildren().isEmpty(),
        "expected at least one split child but list is empty");
    Token child = context.getSplitChildren().get(0);
    byte[] justBytes = child.getGenesis().getJustification().orElseThrow(
        () -> new AssertionError("split child has no justification"));
    original = SplitMintJustification.fromCbor(justBytes);
    originalCbor = justBytes;
  }

  @When("the justification is encoded and decoded back")
  public void justificationEncodedAndDecoded() {
    decoded = SplitMintJustification.fromCbor(original.toCbor());
  }

  @Then("the decoded token's CBOR equals the original token's CBOR")
  public void decodedTokenCborEqualsOriginal() {
    assertArrayEquals(original.getToken().toCbor(), decoded.getToken().toCbor());
  }

  @Then("the decoded proofs equal the original proofs")
  public void decodedProofsEqualOriginal() {
    Set<byte[]> originalSet = new HashSet<>();
    for (SplitAssetProof p : original.getProofs()) {
      originalSet.add(p.toCbor());
    }
    Set<byte[]> decodedSet = new HashSet<>();
    for (SplitAssetProof p : decoded.getProofs()) {
      decodedSet.add(p.toCbor());
    }
    assertTrue(originalSet.size() == decodedSet.size(),
        "different number of proofs: original=" + originalSet.size()
            + " decoded=" + decodedSet.size());
    // Compare by encoded bytes — Set<byte[]> equals doesn't compare contents,
    // so iterate.
    for (byte[] p : original.getProofs().stream()
        .map(SplitAssetProof::toCbor).toArray(byte[][]::new)) {
      boolean found = decoded.getProofs().stream()
          .anyMatch(d -> java.util.Arrays.equals(d.toCbor(), p));
      assertTrue(found, "proof not found in decoded set");
    }
  }

  @When("SplitMintJustification.create is called with an empty proof list")
  public void splitMintJustificationCreateWithEmpty() {
    thrown = null;
    try {
      Token anyToken = context.getSplitChildren().isEmpty()
          ? context.getCurrentToken()
          : context.getSplitChildren().get(0);
      SplitMintJustification.create(anyToken, java.util.Collections.emptySet());
    } catch (Throwable t) {
      thrown = t;
    }
  }

  @Then("an error is thrown with message containing {string}")
  public void anErrorIsThrownWithMessage(String marker) {
    assertNotNull(thrown, "expected error but got success");
    String msg = thrown.getMessage();
    assertTrue(msg != null && msg.toLowerCase().contains(marker.toLowerCase()),
        "expected message containing '" + marker + "' but was: " + msg);
  }

  @When("the justification bytes are decoded via SplitMintJustification.fromCBOR")
  public void justificationBytesDecodedViaFromCbor() {
    thrown = null;
    try {
      decoded = SplitMintJustification.fromCbor(originalCbor);
    } catch (Throwable t) {
      thrown = t;
    }
  }

  @Then("no decoding error is raised")
  public void noDecodingErrorIsRaised() {
    if (thrown != null) {
      fail("expected no error but got: " + thrown.getMessage());
    }
  }
}
