package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.cucumber.java.en.Then;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class TrustBaseSteps {

  private final TestContext context;

  public TrustBaseSteps(TestContext context) {
    this.context = context;
  }

  @Then("the token fails verification against a different trust base")
  public void theTokenFailsVerificationAgainstADifferentTrustBase() {
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token to verify");

    // Spin up an independent mock aggregator — it generates its own trust base
    // rooted in a different key, so our token's certified mint signature will
    // not validate against it.
    TestAggregatorClient rival = TestAggregatorClient.create();
    PredicateVerifierService rivalVerifier = PredicateVerifierService.create();
    org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService rivalMjv =
        new org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService();

    assertEquals(
        VerificationStatus.FAIL,
        token.verify(rival.getTrustBase(), rivalVerifier, rivalMjv).getStatus(),
        "token unexpectedly verified against foreign trust base");
  }
}
