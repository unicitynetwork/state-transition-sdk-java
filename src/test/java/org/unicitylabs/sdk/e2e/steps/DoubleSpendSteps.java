package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.security.SecureRandom;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.e2e.support.ForgivingTestAggregatorClient;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.util.InclusionProofUtils;

/**
 * Steps for double-spend / duplicate-submission scenarios that exercise the
 * forgiving-aggregator model (both submissions accepted → inclusion-proof
 * verification rejects the second with TRANSACTION_HASH_MISMATCH).
 *
 * <p>These scenarios deliberately route around {@link
 * org.unicitylabs.sdk.e2e.support.StrictTestAggregatorClient} — they need the
 * mock to silently accept duplicate state IDs so that the second transaction
 * falls through to inclusion-proof verification.
 */
public class DoubleSpendSteps {

  private final TestContext context;

  private TransferTransaction firstTransferTx;
  private TransferTransaction secondTransferTx;
  private MintTransaction firstMintTx;
  private MintTransaction secondMintTx;
  private CertificationResponse firstResponse;
  private CertificationResponse secondResponse;

  private TokenId reusedTokenId;
  private TokenType reusedTokenType;

  public DoubleSpendSteps(TestContext context) {
    this.context = context;
  }

  @Given("a forgiving aggregator is running")
  public void aForgivingAggregatorIsRunning() {
    // When AGGREGATOR_URL is set, defer to the real aggregator — the user can
    // then observe which double-spend scenarios pass (tells them whether the
    // real aggregator is strict or forgiving in duplicate-submission handling).
    // The AggregatorSteps env-var wiring already handles this.
    if (System.getenv("AGGREGATOR_URL") != null) {
      new AggregatorSteps(context).aMockAggregatorIsRunning();
      return;
    }

    ForgivingTestAggregatorClient forgiving = ForgivingTestAggregatorClient.create();
    context.setAggregatorClient(forgiving);
    context.setClient(new StateTransitionClient(forgiving));
    context.setTrustBase(forgiving.getTrustBase());
    org.unicitylabs.sdk.predicate.verification.PredicateVerifierService predVerifier =
        PredicateVerifierService.create();
    context.setPredicateVerifier(predVerifier);
    org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService mjv =
        new org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService();
    mjv.register(new org.unicitylabs.sdk.payment.SplitMintJustificationVerifier(
        forgiving.getTrustBase(), predVerifier,
        org.unicitylabs.sdk.functional.payment.TestPaymentData::decode));
    context.setMintJustificationVerifier(mjv);
  }

  // ── Double-spend (transfer) path ─────────────────────────────────────────

  @When("{word} submits a valid transfer to {word}")
  public void userSubmitsAValidTransferTo(String sender, String recipient) throws Exception {
    Token source = context.getCurrentToken();
    assertNotNull(source, "no current token");
    ensureUser(recipient);

    firstTransferTx = TransferTransaction.create(source, context.getUserAddresses().get(recipient), randomBytes32(), CborSerializer.encodeArray());

    firstResponse = context.getClient().submitCertificationRequest(
        CertificationData.fromTransaction(
            firstTransferTx,
            SignaturePredicateUnlockScript.create(
                firstTransferTx,
                context.getUserSigningServices().get(sender))))
        .get();
  }

  @When("{word} submits a second transfer of the same token")
  public void userSubmitsASecondTransferOfTheSameToken(String sender) throws Exception {
    Token source = context.getCurrentToken();
    ensureUser("SecondRecipient");
    secondTransferTx = TransferTransaction.create(source, context.getUserAddresses().get("SecondRecipient"), randomBytes32(), CborSerializer.encodeArray());

    secondResponse = context.getClient().submitCertificationRequest(
        CertificationData.fromTransaction(
            secondTransferTx,
            SignaturePredicateUnlockScript.create(
                secondTransferTx,
                context.getUserSigningServices().get(sender))))
        .get();
  }

  // ── Duplicate-mint path ──────────────────────────────────────────────────

  @When("the user submits a mint request for a specific token ID")
  public void theUserSubmitsAMintRequestForASpecificTokenId() throws Exception {
    String user = context.getCurrentUser() != null ? context.getCurrentUser() : "Alice";
    context.setCurrentUser(user);
    reusedTokenId = TokenId.generate();
    reusedTokenType = TokenType.generate();

    firstMintTx = MintTransaction.create(context.getUserAddresses().get(user), reusedTokenId, reusedTokenType, null, CborSerializer.encodeArray());

    firstResponse = context.getClient().submitCertificationRequest(
        CertificationData.fromMintTransaction(firstMintTx))
        .get();
  }

  @When("the user submits a second mint request for the same token ID")
  public void theUserSubmitsASecondMintRequestForTheSameTokenId() throws Exception {
    String user = context.getCurrentUser();
    assertNotNull(reusedTokenId, "no TokenId was recorded from the first mint");

    // Different tx content but SAME token id / recipient → same stateId.
    secondMintTx = MintTransaction.create(context.getUserAddresses().get(user), reusedTokenId, reusedTokenType, null, new byte[] {0x01, 0x02, 0x03}); // different payload → different tx hash

    secondResponse = context.getClient().submitCertificationRequest(
        CertificationData.fromMintTransaction(secondMintTx))
        .get();
  }

  // ── Assertions ───────────────────────────────────────────────────────────

  @Then("the first aggregator response is {string}")
  public void theFirstAggregatorResponseIs(String expected) {
    assertNotNull(firstResponse, "no first response captured");
    assertEquals(expected, firstResponse.getStatus().name());
  }

  @Then("the second aggregator response is {string}")
  public void theSecondAggregatorResponseIs(String expected) {
    assertNotNull(secondResponse, "no second response captured");
    assertEquals(expected, secondResponse.getStatus().name());
  }

  @Then("the inclusion proof verification rejects the second transfer with {string}")
  public void theInclusionProofVerificationRejectsTheSecondTransferWith(String errorMarker)
      throws Exception {
    assertNotNull(secondTransferTx, "no second transfer captured");
    try {
      InclusionProofUtils.waitInclusionProof(
          context.getClient(),
          context.getTrustBase(),
          context.getPredicateVerifier(),
          secondTransferTx).get();
      fail("expected inclusion-proof verification to reject with " + errorMarker);
    } catch (Exception e) {
      // The actual exception type / message carries the status enum value.
      // We assert the error marker appears somewhere in the chain.
      assertStatusMentioned(e, errorMarker);
    }
  }

  @Then("the inclusion proof verification rejects the second mint with {string}")
  public void theInclusionProofVerificationRejectsTheSecondMintWith(String errorMarker)
      throws Exception {
    assertNotNull(secondMintTx, "no second mint captured");
    try {
      InclusionProofUtils.waitInclusionProof(
          context.getClient(),
          context.getTrustBase(),
          context.getPredicateVerifier(),
          secondMintTx).get();
      fail("expected inclusion-proof verification to reject with " + errorMarker);
    } catch (Exception e) {
      assertStatusMentioned(e, errorMarker);
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  private void ensureUser(String userName) {
    if (!context.getUserSigningServices().containsKey(userName)) {
      new UserSteps(context).userHasASigningKey(userName);
    }
  }

  private static byte[] randomBytes32() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return b;
  }

  private static void assertStatusMentioned(Throwable e, String marker) {
    Throwable t = e;
    while (t != null) {
      if (t.getMessage() != null && t.getMessage().contains(marker)) {
        return;
      }
      t = t.getCause();
    }
    fail("expected status '" + marker + "' in exception chain but got: " + e);
  }
}
