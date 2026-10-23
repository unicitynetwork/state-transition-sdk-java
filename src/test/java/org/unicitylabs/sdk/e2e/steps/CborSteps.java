package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.security.SecureRandom;
import java.util.Arrays;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class CborSteps {

  private final TestContext context;

  public CborSteps(TestContext context) {
    this.context = context;
  }

  @When("the token is exported to CBOR")
  public void theTokenIsExportedToCbor() {
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token to export");
    context.setExportedTokenCbor(token.toCbor());
  }

  @When("the CBOR data is imported back to a token")
  public void theCborDataIsImportedBackToAToken() {
    byte[] cbor = context.getExportedTokenCbor();
    assertNotNull(cbor, "no exported CBOR to import");
    Token imported = Token.fromCbor(cbor);
    context.setImportedToken(imported);
  }

  @When("the CBOR data is truncated to half its length")
  public void theCborDataIsTruncatedToHalfItsLength() {
    byte[] cbor = context.getExportedTokenCbor();
    assertNotNull(cbor, "no exported CBOR to truncate");
    byte[] truncated = Arrays.copyOf(cbor, cbor.length / 2);
    context.setExportedTokenCbor(truncated);
  }

  @When("random bytes are used as token CBOR data")
  public void randomBytesAreUsedAsTokenCborData() {
    byte[] random = new byte[256];
    new SecureRandom().nextBytes(random);
    context.setExportedTokenCbor(random);
  }

  @Then("importing the corrupted CBOR data fails with an error")
  public void importingTheCorruptedCborDataFailsWithAnError() {
    byte[] cbor = context.getExportedTokenCbor();
    assertNotNull(cbor, "no exported CBOR to test");
    try {
      Token.fromCbor(cbor);
      fail("expected Token.fromCbor to throw on corrupted input");
    } catch (RuntimeException expected) {
      // Expected — corrupted CBOR should be rejected.
    }
  }

  @Then("the imported token has the same ID as the original")
  public void theImportedTokenHasTheSameIdAsTheOriginal() {
    Token imported = context.getImportedToken();
    Token original = context.getCurrentToken();
    assertNotNull(imported, "no imported token");
    assertNotNull(original, "no original token");
    assertEquals(original.getId(), imported.getId(), "token IDs differ after CBOR roundtrip");
  }

  @Then("the imported token passes verification")
  public void theImportedTokenPassesVerification() {
    Token imported = context.getImportedToken();
    assertNotNull(imported, "no imported token");
    assertEquals(
        VerificationStatus.OK,
        imported.verify(context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier()).getStatus(),
        "imported token verification failed");
  }

  @Then("the imported token has {int} transaction in its history")
  public void theImportedTokenHasTransactionsInItsHistory(int expected) {
    Token imported = context.getImportedToken();
    assertNotNull(imported, "no imported token");
    assertEquals(
        expected,
        imported.getTransactions().size(),
        "unexpected transaction history length after CBOR roundtrip");
  }

  @Then("the imported token has the same type as the original")
  public void theImportedTokenHasTheSameTypeAsTheOriginal() {
    Token imported = context.getImportedToken();
    Token original = context.getCurrentToken();
    assertNotNull(imported, "no imported token");
    assertNotNull(original, "no original token");
    assertEquals(original.getType(), imported.getType(),
        "token types differ after CBOR roundtrip");
  }

  // Aliases used by token-serialization-advanced.feature.

  @When("the current token is exported to CBOR")
  public void theCurrentTokenIsExportedToCbor() {
    theTokenIsExportedToCbor();
  }

  @Then("the imported token should have the same ID as the current token")
  public void theImportedTokenShouldHaveTheSameIdAsTheCurrentToken() {
    theImportedTokenHasTheSameIdAsTheOriginal();
  }

  @Then("the imported token should have {int} transaction in its history")
  public void theImportedTokenShouldHaveNTransactionInHistory(int expected) {
    theImportedTokenHasTransactionsInItsHistory(expected);
  }

  @Then("the imported token should have {int} transactions in its history")
  public void theImportedTokenShouldHaveNTransactionsInHistory(int expected) {
    theImportedTokenHasTransactionsInItsHistory(expected);
  }

  @Then("the imported token should pass verification")
  public void theImportedTokenShouldPassVerification() {
    theImportedTokenPassesVerification();
  }

  @When("{word} transfers the imported token to {word}")
  public void userTransfersTheImportedTokenTo(String sender, String recipient) throws Exception {
    // Treat the imported token as the current token for the ensuing transfer.
    Token imported = context.getImportedToken();
    assertNotNull(imported, "no imported token to transfer");
    context.setCurrentToken(imported);
    // Delegate — relies on the {word} transfer step in TokenLifecycleSteps.
    new TokenLifecycleSteps(context).userTransfersTheCurrentTokenToRecipient(sender, recipient);
  }
}
