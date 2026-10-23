package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class VerificationSteps {

  private final TestContext context;
  private VerificationResult<VerificationStatus> lastResult;

  public VerificationSteps(TestContext context) {
    this.context = context;
  }

  @When("the token is verified against the trust base")
  public void theTokenIsVerifiedAgainstTheTrustBase() {
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token to verify");
    lastResult = token.verify(context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier());
  }

  @When("the transferred token is verified against the trust base")
  public void theTransferredTokenIsVerifiedAgainstTheTrustBase() {
    theTokenIsVerifiedAgainstTheTrustBase();
  }

  @Then("the verification result has rule {string} with status {string}")
  public void theVerificationResultHasRuleWithStatus(String rule, String status) {
    assertNotNull(lastResult, "no verification result captured");
    assertEquals(rule, lastResult.getRule(), "unexpected top-level rule");
    assertEquals(VerificationStatus.valueOf(status), lastResult.getStatus(),
        "unexpected top-level status");
  }

  @Then("the verification result contains {int} sub-results")
  public void theVerificationResultContainsSubResults(int expected) {
    assertNotNull(lastResult, "no verification result captured");
    assertEquals(expected, lastResult.getResults().size(),
        "unexpected sub-result count");
  }

  @Then("sub-result {int} has rule {string} with status {string}")
  public void subResultHasRuleWithStatus(int index, String rule, String status) {
    assertNotNull(lastResult, "no verification result captured");
    assertTrue(lastResult.getResults().size() >= index,
        "sub-result index " + index + " out of range");
    VerificationResult<?> sub = lastResult.getResults().get(index - 1);
    assertEquals(rule, sub.getRule(), "unexpected sub-result rule at index " + index);
    assertEquals(status, sub.getStatus().toString(),
        "unexpected sub-result status at index " + index);
  }

  @Then("the transfer verification sub-result contains {int} entry")
  public void transferVerificationSubResultContains1Entry(int expected) {
    transferVerificationSubResultContainsNEntries(expected);
  }

  @Then("the transfer verification sub-result contains {int} entries")
  public void transferVerificationSubResultContainsNEntries(int expected) {
    assertNotNull(lastResult, "no verification result captured");
    VerificationResult<?> transferResult = lastResult.getResults().stream()
        .filter(r -> "TokenTransferVerification".equals(r.getRule()))
        .findFirst()
        .orElse(null);
    assertNotNull(transferResult, "no TokenTransferVerification sub-result found");
    assertEquals(expected, transferResult.getResults().size(),
        "unexpected transfer sub-result count");
  }
}
