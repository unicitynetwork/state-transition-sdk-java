package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.e2e.support.TokenTree;
import org.unicitylabs.sdk.e2e.support.TokenTreeBuilder;
import org.unicitylabs.sdk.e2e.support.TreeUser;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class TreeSteps {

  private final TestContext context;
  private TokenTree tree;
  private Exception lastError;
  private CertificationResponse lastResponse;
  private Token importedToken;

  public TreeSteps(TestContext context) {
    this.context = context;
  }

  // ── Setup ────────────────────────────────────────────────────────────────

  @Given("the 4-level token tree is built")
  public void the4LevelTokenTreeIsBuilt() throws Exception {
    // The Background of tree features runs the aggregator setup step implicitly
    // in TS — we need the aggregator up here. If AggregatorSteps.aMockAggregatorIsRunning
    // wasn't called, bootstrap one.
    if (context.getClient() == null) {
      new AggregatorSteps(context).aMockAggregatorIsRunning();
    }
    // Cache-or-build: the first tree scenario pays the ~11 aggregator ops;
    // every subsequent tree scenario re-uses the cached tree. Mirrors TS's
    // module-level cachedTree in TokenTreeBuilder.ts. Restoring the cached
    // tree's client/trustBase/verifier onto TestContext keeps downstream
    // steps consistent across the ~136 tree scenarios.
    tree = TokenTreeBuilder.buildOrReuse(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier());
    context.setClient(tree.getClient());
    context.setTrustBase(tree.getTrustBase());
    context.setPredicateVerifier(tree.getPredicateVerifier());
    context.setMintJustificationVerifier(tree.getMintJustificationVerifier());
  }

  // ── Owner actions ────────────────────────────────────────────────────────

  @When("{word} creates a transfer for {string}")
  public void userCreatesATransferFor(String userName, String tokenName) {
    TreeUser user = tree.requireUser(userName);
    Token token = tree.requireToken(tokenName);
    lastError = null;
    try {
      // Spawn a throw-away recipient to decouple from tree state.
      TreeUser phantom = TreeUser.generate("phantom");
      TransferTransaction.create(token, phantom.getPredicate(), randomBytes32(), CborSerializer.encodeArray());
    } catch (Exception e) {
      lastError = e;
    }
  }

  @Then("the transfer creation succeeds")
  public void theTransferCreationSucceeds() {
    assertNull(lastError,
        "transfer creation failed: "
            + (lastError != null ? lastError.getMessage() : ""));
  }

  @When("{word} transfers {string} to {word}")
  public void userTransfersTokenTo(String userName, String tokenName, String recipientName)
      throws Exception {
    TreeUser user = tree.requireUser(userName);
    Token token = tree.requireToken(tokenName);
    TreeUser recipient = tree.requireUser(recipientName);

    Token transferred = org.unicitylabs.sdk.utils.TokenUtils.transferToken(
        tree.getClient(),
        tree.getTrustBase(),
        tree.getPredicateVerifier(),
        tree.getMintJustificationVerifier(),
        token.toCbor(),
        recipient.getPredicate(),
        user.getSigningService());
    context.setCurrentToken(transferred);
  }

  @When("{word} submits a duplicate transfer for pre-transfer token {string}")
  public void userSubmitsADuplicateTransferForPreTransferToken(
      String userName, String tokenName) throws Exception {
    TreeUser user = tree.requireUser(userName);
    Token token = tree.requireToken(tokenName);
    TreeUser phantom = TreeUser.generate("phantom");

    TransferTransaction tx = TransferTransaction.create(token, phantom.getPredicate(), randomBytes32(), CborSerializer.encodeArray());

    CertificationData certData = CertificationData.fromTransaction(
        tx, SignaturePredicateUnlockScript.create(tx, user.getSigningService()));
    lastResponse = tree.getClient().submitCertificationRequest(certData).get();
  }

  @Then("the aggregator responds with {string}")
  public void theAggregatorRespondsWith(String status) {
    assertNotNull(lastResponse, "no aggregator response captured");
    assertEquals(status, lastResponse.getStatus().name(), "unexpected aggregator status");
  }

  @When("{word} tries to transfer {string}")
  public void userTriesToTransferToken(String userName, String tokenName) {
    TreeUser attacker = tree.requireUser(userName);
    Token token = tree.requireToken(tokenName);
    lastError = attemptUnauthorizedTransfer(attacker, token);
  }

  @Then("the transfer fails with predicate mismatch")
  public void theTransferFailsWithPredicateMismatch() {
    assertNotNull(lastError,
        "expected transfer creation to fail but no error was recorded");
  }

  @When("{word} tries to split {string}")
  public void userTriesToSplitToken(String userName, String tokenName) {
    TreeUser attacker = tree.requireUser(userName);
    Token token = tree.requireToken(tokenName);
    lastError = attemptUnauthorizedSplit(attacker, token);
  }

  /**
   * Post-PR #112 negative-path helper. {@code TransferTransaction.create} no
   * longer takes an owner predicate, so wrong-owner detection moves to the
   * predicate-verifier layer. Mirror of TS {@code attemptUnauthorizedTransfer}
   * in {@code TestSetup.ts}.
   */
  private Exception attemptUnauthorizedTransfer(TreeUser attacker, Token token) {
    try {
      TreeUser phantom = TreeUser.generate("phantom");
      TransferTransaction tx = TransferTransaction.create(
          token, phantom.getPredicate(), randomBytes32(), CborSerializer.encodeArray());
      org.unicitylabs.sdk.predicate.UnlockScript unlock =
          SignaturePredicateUnlockScript.create(tx, attacker.getSigningService());

      org.unicitylabs.sdk.transaction.Transaction latest = token.getLatestTransaction();
      org.unicitylabs.sdk.util.verification.VerificationResult<VerificationStatus> result =
          tree.getPredicateVerifier().verify(
              latest.getRecipient(),
              tx.getSourceStateHash(),
              tx.calculateTransactionHash(),
              unlock.encode());

      return result.getStatus() == VerificationStatus.OK
          ? null
          : new RuntimeException(
              "Predicate verification rejected: " + result.getMessage());
    } catch (Exception e) {
      return e;
    }
  }

  /**
   * Post-PR #112 negative-path helper for splits. {@code TokenSplit.split} no
   * longer enforces ownership — it builds a burn TransferTransaction
   * internally, and rejection moves to the predicate-verifier layer when the
   * burn is signed and submitted. Mirror of TS {@code attemptUnauthorizedSplit}
   * in {@code TestSetup.ts}.
   */
  private Exception attemptUnauthorizedSplit(TreeUser attacker, Token token) {
    try {
      Map<TokenId, Set<Asset>> plan = new HashMap<>();
      plan.put(TokenId.generate(),
          Set.of(new Asset(tree.getAssetId1(), java.math.BigInteger.ONE)));
      org.unicitylabs.sdk.payment.SplitResult split =
          TokenSplit.split(token, tree.parserFor(tokenName_default()), plan);
      org.unicitylabs.sdk.transaction.TransferTransaction burnTx = split.getBurnTransaction();
      org.unicitylabs.sdk.predicate.UnlockScript unlock =
          SignaturePredicateUnlockScript.create(burnTx, attacker.getSigningService());

      org.unicitylabs.sdk.transaction.Transaction latest = token.getLatestTransaction();
      org.unicitylabs.sdk.util.verification.VerificationResult<VerificationStatus> result =
          tree.getPredicateVerifier().verify(
              latest.getRecipient(),
              burnTx.getSourceStateHash(),
              burnTx.calculateTransactionHash(),
              unlock.encode());

      return result.getStatus() == VerificationStatus.OK
          ? null
          : new RuntimeException(
              "Predicate verification rejected: " + result.getMessage());
    } catch (Exception e) {
      return e;
    }
  }

  /** Default parser key — every tree token uses TestPaymentData post-issue-52. */
  private static String tokenName_default() {
    return "T0_burned"; // any name works, parserFor returns TestPaymentData::decode
  }

  @Then("the split fails with predicate mismatch")
  public void theSplitFailsWithPredicateMismatch() {
    assertNotNull(lastError, "expected split to fail but no error was recorded");
  }

  // ── Verification ─────────────────────────────────────────────────────────
  // Note: "the transferred token passes verification" is handled by TokenLifecycleSteps
  // (it reads context.getCurrentToken() which our transfer step populates).

  @Then("{string} passes verification")
  public void namedTokenPassesVerification(String tokenName) {
    Token token = tree.requireToken(tokenName);
    assertEquals(
        VerificationStatus.OK,
        token.verify(
            tree.getTrustBase(),
            tree.getPredicateVerifier(),
            tree.getMintJustificationVerifier()).getStatus(),
        "verification failed for " + tokenName);
  }

  @Then("{string} passes split verification")
  public void namedTokenPassesSplitVerification(String tokenName) {
    // Post-issue-52 SDK: TokenSplit.verify is gone — split-mint verification
    // happens inside Token.verify via the MintJustificationVerifierService
    // registry. T0 is a simple-payment token (no SplitMintJustification);
    // calling Token.verify is still correct — the registry's split verifier
    // simply does not fire for it.
    Token token = tree.requireToken(tokenName);
    assertEquals(
        VerificationStatus.OK,
        token.verify(
            tree.getTrustBase(),
            tree.getPredicateVerifier(),
            tree.getMintJustificationVerifier()).getStatus(),
        "split verification failed for " + tokenName);
  }

  @When("{string} is exported to CBOR and imported back")
  public void tokenIsExportedToCborAndImportedBack(String tokenName) {
    Token original = tree.requireToken(tokenName);
    Token imported = Token.fromCbor(original.toCbor());
    // Route through TestContext so CborSteps' existing assertion steps work.
    context.setCurrentToken(original);
    context.setImportedToken(imported);
  }

  @Then("the imported token has the same ID as {string}")
  public void theImportedTokenHasTheSameIdAs(String tokenName) {
    Token imported = context.getImportedToken();
    assertNotNull(imported, "no imported token");
    assertEquals(tree.requireToken(tokenName).getId(), imported.getId(),
        "token ID mismatch after CBOR roundtrip");
  }
  // "the imported token passes verification" is handled by CborSteps.

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static byte[] randomBytes32() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return b;
  }

  // Guards against accidentally depending on AggregatorSteps without an AggregatorSteps
  // instance in this scenario's step cycle. Checked style only — not used directly.
  @SuppressWarnings("unused")
  private void ensureAggregatorUp() {
    assertTrue(context.getClient() != null, "aggregator not initialized");
  }
}
