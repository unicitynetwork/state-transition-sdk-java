package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.cucumber.java.DataTableType;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.List;
import java.util.Map;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;

import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

public class ChainSteps {

  private final TestContext context;

  public ChainSteps(TestContext context) {
    this.context = context;
  }

  // ── User setup ──────────────────────────────────────────────────────────

  @DataTableType
  public String userNameEntry(Map<String, String> row) {
    return row.get("name");
  }

  @Given("the following users are registered:")
  public void theFollowingUsersAreRegistered(List<String> names) {
    for (String name : names) {
      ensureUser(name);
    }
  }

  // Note: "Alice has a minted token" is handled by TokenLifecycleSteps
  // ({word} variant). We augment by snapshotting the original for
  // same-ID/same-type chain assertions.

  @Given("the original minted token is snapshotted for chain assertions")
  public void snapshotOriginalMintedToken() {
    Token current = context.getCurrentToken();
    assertNotNull(current, "no current token to snapshot");
    context.setOriginalToken(current);
  }

  @When("the token is transferred {int} times between {word} and {word}")
  public void theTokenIsTransferredNTimesBetween(int hops, String userA, String userB)
      throws Exception {
    ensureUser(userA);
    ensureUser(userB);
    // Ping-pong: hop 1 sends to the *other* user relative to the current owner.
    for (int i = 0; i < hops; i++) {
      String sender = context.getCurrentUser();
      String recipient = sender.equals(userA) ? userB : userA;
      Token source = context.getCurrentToken();
      Token transferred = TokenUtils.transferToken(
          context.getClient(),
          context.getTrustBase(),
          context.getPredicateVerifier(),
          context.getMintJustificationVerifier(),
          source.toCbor(),
          context.getUserAddresses().get(recipient),
          context.getUserSigningServices().get(sender));
      context.addUserToken(recipient, transferred);
      context.setCurrentToken(transferred);
      context.setCurrentUser(recipient);
    }
  }

  // ── Assertions ───────────────────────────────────────────────────────────

  @Then("the token should have {int} transactions in its history")
  public void theTokenShouldHaveNTransactionsInHistory(int expected) {
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token");
    assertEquals(expected, token.getTransactions().size(),
        "unexpected transfer-history length");
  }

  @Then("the token should pass verification")
  public void theTokenShouldPassVerification() {
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token");
    assertEquals(
        VerificationStatus.OK,
        token.verify(context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier()).getStatus(),
        "token verification failed");
  }

  @Then("the token should have the same ID as the original")
  public void theTokenShouldHaveTheSameIdAsOriginal() {
    Token current = context.getCurrentToken();
    Token original = context.getOriginalToken();
    assertNotNull(current);
    assertNotNull(original, "no original-token snapshot captured");
    assertEquals(original.getId(), current.getId(), "token ID drift through chain");
  }

  @Then("the token should have the same type as the original")
  public void theTokenShouldHaveTheSameTypeAsOriginal() {
    Token current = context.getCurrentToken();
    Token original = context.getOriginalToken();
    assertNotNull(current);
    assertNotNull(original, "no original-token snapshot captured");
    assertEquals(original.getType(), current.getType(), "token type drift through chain");
  }

  @Then("{word} should own the token")
  public void userShouldOwnTheToken(String userName) {
    assertEquals(userName, context.getCurrentUser(),
        "token owner mismatch: expected " + userName + " but currentUser is "
            + context.getCurrentUser());
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private void ensureUser(String name) {
    if (!context.getUserSigningServices().containsKey(name)) {
      SigningService signing = SigningService.generate();
      SignaturePredicate predicate = SignaturePredicate.create(signing.getPublicKey());
      Predicate address = predicate;
      context.getUserSigningServices().put(name, signing);
      context.getUserPredicates().put(name, predicate);
      context.getUserAddresses().put(name, address);
    }
  }
}
