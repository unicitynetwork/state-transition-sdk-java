package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.security.SecureRandom;
import java.util.List;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

public class TokenLifecycleSteps {

  private final TestContext context;

  public TokenLifecycleSteps(TestContext context) {
    this.context = context;
  }

  // ─── Mint ──────────────────────────────────────────────────────────────────

  @When("{word} mints a token")
  public void userMintsAToken(String userName) throws Exception {
    Predicate recipient = requireAddress(userName);
    Token token = TokenUtils.mintToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        recipient
    );
    context.addUserToken(userName, token);
    context.setCurrentToken(token);
    context.setCurrentUser(userName);
    if (context.getOriginalToken() == null) {
      context.setOriginalToken(token);
    }
  }

  @Given("{word} has a minted token")
  public void userHasAMintedToken(String userName) throws Exception {
    userMintsAToken(userName);
  }

  // ─── Transfer ──────────────────────────────────────────────────────────────

  @When("{word} transfers the current token to {word}")
  public void userTransfersTheCurrentTokenToRecipient(String sender, String recipient)
      throws Exception {
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token to transfer");

    Predicate recipientPredicate = requireAddress(recipient);
    SigningService senderSigning = context.getUserSigningServices().get(sender);
    assertNotNull(senderSigning, "no signing key for sender " + sender);

    Token transferred = TokenUtils.transferToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        sourceToken.toCbor(),
        recipientPredicate,
        senderSigning
    );

    context.addUserToken(recipient, transferred);
    context.setCurrentToken(transferred);
    context.setCurrentUser(recipient);
  }

  @When("{word} transfers the token to {word}")
  public void userTransfersTheTokenTo(String sender, String recipient) throws Exception {
    userTransfersTheCurrentTokenToRecipient(sender, recipient);
  }

  @When("{word} transfers the token to {word} with 10KB of random data")
  public void userTransfersTheTokenToRecipientWithLargeData(String sender, String recipient)
      throws Exception {
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token to transfer");

    byte[] data = new byte[10 * 1024];
    new SecureRandom().nextBytes(data);

    Predicate recipientPredicate = requireAddress(recipient);
    SigningService senderSigning = context.getUserSigningServices().get(sender);
    assertNotNull(senderSigning, "no signing key for sender " + sender);

    byte[] x = new byte[32];
    new SecureRandom().nextBytes(x);

    TransferTransaction tx = TransferTransaction.create(
        sourceToken, recipientPredicate, x, data);

    Token transferred = TokenUtils.transferToken(
        context.getClient(), context.getTrustBase(),
        context.getPredicateVerifier(),
        sourceToken,
        tx,
        SignaturePredicateUnlockScript.create(tx, senderSigning));

    context.addUserToken(recipient, transferred);
    context.setCurrentToken(transferred);
    context.setCurrentUser(recipient);
  }

  @When("{word} creates a transfer to {word} signed with the wrong key")
  public void userCreatesTransferSignedWithWrongKey(String sender, String recipient)
      throws Exception {
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token");
    Predicate recipientPredicate = requireAddress(recipient);

    SigningService ownerSigning = context.getUserSigningServices().get(sender);
    assertNotNull(ownerSigning, "no signing key for " + sender);

    SigningService wrongSigning = SigningService.generate();

    byte[] x = new byte[32];
    new SecureRandom().nextBytes(x);

    TransferTransaction tx = TransferTransaction.create(
        sourceToken, recipientPredicate, x, new byte[0]);

    CertificationData certificationData = CertificationData.fromTransaction(
        tx, SignaturePredicateUnlockScript.create(tx, wrongSigning));

    CertificationResponse response =
        context.getClient().submitCertificationRequest(certificationData).get();
    context.setLastCertificationResponse(response);
  }

  @When("{word} tries to submit a transfer of the stale token to {word}")
  public void userTriesToSubmitATransferOfTheStaleTokenTo(String sender, String recipient)
      throws Exception {
    List<Token> senderTokens = context.getUserTokens().get(sender);
    assertNotNull(senderTokens, "no stale token recorded for " + sender);
    Token staleToken = senderTokens.get(0);

    Predicate recipientPredicate = requireAddress(recipient);
    SigningService senderSigning = context.getUserSigningServices().get(sender);
    assertNotNull(senderSigning, "no signing key for sender " + sender);

    byte[] x = new byte[32];
    new SecureRandom().nextBytes(x);

    TransferTransaction tx = TransferTransaction.create(
        staleToken, recipientPredicate, x, new byte[0]);

    CertificationData certificationData = CertificationData.fromTransaction(
        tx, SignaturePredicateUnlockScript.create(tx, senderSigning));

    CertificationResponse response =
        context.getClient().submitCertificationRequest(certificationData).get();
    context.setLastCertificationResponse(response);
  }

  @When("the user mints a token with empty transaction data")
  public void theUserMintsATokenWithEmptyTransactionData() throws Exception {
    mintForCurrentUserWithData(new byte[0]);
  }

  @When("the user mints a token with 10KB of random transaction data")
  public void theUserMintsATokenWith10KbOfRandomData() throws Exception {
    byte[] data = new byte[10 * 1024];
    new SecureRandom().nextBytes(data);
    mintForCurrentUserWithData(data);
  }

  // ─── Verify ────────────────────────────────────────────────────────────────

  @Then("the current token verifies successfully")
  public void theCurrentTokenVerifiesSuccessfully() {
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token to verify");
    assertEquals(
        VerificationStatus.OK,
        token.verify(
            context.getTrustBase(),
            context.getPredicateVerifier(),
            context.getMintJustificationVerifier()).getStatus(),
        "token verification failed");
  }

  @Then("the transferred token passes verification")
  public void theTransferredTokenPassesVerification() {
    theCurrentTokenVerifiesSuccessfully();
  }

  @Then("the certification response status is {string}")
  public void theCertificationResponseStatusIs(String expected) {
    if (context.getLastCertificationResponse() != null) {
      assertEquals(
          expected,
          context.getLastCertificationResponse().getStatus().name(),
          "unexpected certification status");
      return;
    }
    if ("SUCCESS".equals(expected)) {
      assertNotNull(context.getCurrentToken(), "expected SUCCESS but no token was produced");
    } else {
      throw new AssertionError(
          "expected certification status " + expected
              + " but no CertificationResponse was captured for assertion");
    }
  }

  @Then("the current token is owned by {word}")
  public void theCurrentTokenIsOwnedBy(String userName) {
    assertEquals(userName, context.getCurrentUser());
    assertNotNull(
        context.getUserTokens().get(userName),
        "no tokens recorded for user " + userName);
  }

  private Predicate requireAddress(String user) {
    Predicate predicate = context.getUserAddresses().get(user);
    if (predicate == null) {
      SigningService signing = SigningService.generate();
      SignaturePredicate sigPredicate = SignaturePredicate.create(signing.getPublicKey());
      predicate = sigPredicate;
      context.getUserSigningServices().put(user, signing);
      context.getUserPredicates().put(user, sigPredicate);
      context.getUserAddresses().put(user, predicate);
    }
    return predicate;
  }

  private void mintForCurrentUserWithData(byte[] data) throws Exception {
    String user = context.getCurrentUser();
    assertNotNull(user, "no current user — call 'a user with a signing key' first");
    Predicate recipient = requireAddress(user);
    Token token = TokenUtils.mintToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        recipient,
        null,
        data);
    context.addUserToken(user, token);
    context.setCurrentToken(token);
  }
}
