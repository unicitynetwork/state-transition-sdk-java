package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

public class MintingSteps {

  private final TestContext context;
  private TokenId expectedId;
  private TokenType expectedType;

  public MintingSteps(TestContext context) {
    this.context = context;
  }

  @When("the user mints a new token")
  public void theUserMintsANewToken() throws Exception {
    String user = context.getCurrentUser() != null ? context.getCurrentUser() : "Alice";
    context.setCurrentUser(user);
    Predicate recipient = context.getUserAddresses().get(user);
    assertNotNull(recipient, "user " + user + " has no address");

    MintTransaction transaction = MintTransaction.create(recipient, TokenId.generate(), TokenType.generate(), null, CborSerializer.encodeArray());

    CertificationData certificationData = CertificationData.fromMintTransaction(transaction);
    CertificationResponse response =
        context.getClient().submitCertificationRequest(certificationData).get();
    context.setLastCertificationResponse(response);

    if (response.getStatus().name().equals("SUCCESS")) {
      Token token = TokenUtils.mintToken(context.getClient(), context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier(), recipient);
      context.addUserToken(user, token);
      context.setCurrentToken(token);
    }
  }

  @When("the user mints a new token with specific token ID and type")
  public void theUserMintsANewTokenWithSpecificTokenIdAndType() throws Exception {
    String user = context.getCurrentUser() != null ? context.getCurrentUser() : "Alice";
    context.setCurrentUser(user);
    Predicate recipient = context.getUserAddresses().get(user);
    assertNotNull(recipient, "user " + user + " has no address");

    expectedId = TokenId.generate();
    expectedType = TokenType.generate();

    Token token = TokenUtils.mintToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        expectedId,
        expectedType,
        recipient,
        null,
        CborSerializer.encodeArray());

    context.addUserToken(user, token);
    context.setCurrentToken(token);
  }

  @Then("the token ID matches the mint parameters")
  public void theTokenIdMatchesTheMintParameters() {
    assertNotNull(expectedId, "no expected TokenId captured");
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token");
    assertEquals(expectedId, token.getId(), "token ID mismatch");
  }

  @Then("the token type matches the mint parameters")
  public void theTokenTypeMatchesTheMintParameters() {
    assertNotNull(expectedType, "no expected TokenType captured");
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token");
    assertEquals(expectedType, token.getType(), "token type mismatch");
  }

  @Then("the token passes verification")
  public void theTokenPassesVerification() {
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token");
    assertEquals(
        VerificationStatus.OK,
        token.verify(context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier()).getStatus());
  }
}
