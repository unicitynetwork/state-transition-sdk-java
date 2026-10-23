package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.ArrayList;
import java.util.List;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Steps for {@code inclusion-cert-stress.feature}: loop test on back-to-back
 * mints, mint+5 transfer chain, duplicate-state resubmission.
 */
public class InclusionCertStressSteps {

  private final TestContext context;

  private List<Token> stressMintedTokens = new ArrayList<>();
  private CertificationData rememberedCert;
  private CertificationResponse resubmissionResponse;

  public InclusionCertStressSteps(TestContext context) {
    this.context = context;
  }

  @When("{int} tokens are minted in a row by the same user")
  public void nTokensAreMintedInARow(int count) throws Exception {
    SigningService signing = SigningService.generate();
    SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);
    stressMintedTokens.clear();
    for (int i = 0; i < count; i++) {
      Token t = TokenUtils.mintToken(
          context.getClient(),
          context.getTrustBase(),
          context.getPredicateVerifier(),
          context.getMintJustificationVerifier(),
          predicate);
      stressMintedTokens.add(t);
    }
  }

  @Then("every minted token passes verification")
  public void everyMintedTokenPassesVerification() {
    for (Token t : stressMintedTokens) {
      assertEquals(
          VerificationStatus.OK,
          t.verify(
              context.getTrustBase(),
              context.getPredicateVerifier(),
              context.getMintJustificationVerifier()).getStatus(),
          "verification failed for token " + t.getId());
    }
  }

  @When("Alice mints a token and transfers it through {int} owners")
  public void aliceMintsAndTransfersThrough(int hops) throws Exception {
    SigningService aliceSigning = SigningService.generate();
    Token current = TokenUtils.mintToken(
        context.getClient(), context.getTrustBase(), context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        SignaturePredicate.fromSigningService(aliceSigning));

    SigningService prevSigning = aliceSigning;
    for (int i = 0; i < hops; i++) {
      SigningService nextSigning = SigningService.generate();
      SignaturePredicate nextPredicate = SignaturePredicate.fromSigningService(nextSigning);
      current = TokenUtils.transferToken(
          context.getClient(), context.getTrustBase(), context.getPredicateVerifier(),
          context.getMintJustificationVerifier(),
          current.toCbor(),
          nextPredicate,
          prevSigning);
      prevSigning = nextSigning;
    }
    context.setCurrentToken(current);
  }

  @Then("the final token has {int} transactions in its history")
  public void theFinalTokenHasNTransactions(int expected) {
    Token t = context.getCurrentToken();
    assertNotNull(t);
    assertEquals(expected, t.getTransactions().size());
  }

  @Then("the final token passes verification")
  public void theFinalTokenPassesVerification() {
    Token t = context.getCurrentToken();
    assertNotNull(t);
    assertEquals(
        VerificationStatus.OK,
        t.verify(
            context.getTrustBase(),
            context.getPredicateVerifier(),
            context.getMintJustificationVerifier()).getStatus());
  }

  @When("the same certification data is re-submitted")
  public void theSameCertificationDataIsResubmitted() throws Exception {
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token");
    SigningService aliceSigning = context.getUserSigningServices().get("Alice");
    SignaturePredicate phantom =
        SignaturePredicate.fromSigningService(SigningService.generate());

    byte[] x = new byte[32];
    new java.security.SecureRandom().nextBytes(x);
    TransferTransaction tx = TransferTransaction.create(
        sourceToken, phantom, x, CborSerializer.encodeArray());

    rememberedCert = CertificationData.fromTransaction(
        tx, SignaturePredicateUnlockScript.create(tx, aliceSigning));

    // First submission
    context.getClient().submitCertificationRequest(rememberedCert).get();
    // Re-submission of identical bytes
    resubmissionResponse = context.getClient().submitCertificationRequest(rememberedCert).get();
  }

  @Then("the re-submission's status is {string}")
  public void theResubmissionStatusIs(String expected) {
    assertNotNull(resubmissionResponse, "no resubmission response captured");
    assertEquals(expected, resubmissionResponse.getStatus().name());
  }

  @Then("the re-submission's status is one of {string} or {string}")
  public void theResubmissionStatusIsOneOf(String first, String second) {
    assertNotNull(resubmissionResponse, "no resubmission response captured");
    String actual = resubmissionResponse.getStatus().name();
    if (!actual.equals(first) && !actual.equals(second)) {
      throw new AssertionError(
          "expected status to be '" + first + "' or '" + second + "' but was: " + actual);
    }
  }
}
