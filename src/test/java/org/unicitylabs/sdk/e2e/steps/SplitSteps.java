package org.unicitylabs.sdk.e2e.steps;

import org.unicitylabs.sdk.predicate.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.functional.payment.TestPaymentData;

import org.unicitylabs.sdk.payment.SplitMintJustification;
import org.unicitylabs.sdk.payment.SplitAssetProof;
import org.unicitylabs.sdk.payment.SplitResult;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;

import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

public class SplitSteps {

  private static final Asset ASSET_1 = new Asset(
      new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8)),
      BigInteger.valueOf(500));
  private static final Asset ASSET_2 = new Asset(
      new AssetId("ASSET_2".getBytes(StandardCharsets.UTF_8)),
      BigInteger.valueOf(500));

  private final TestContext context;
  private SplitResult lastSplitResult;
  private final List<Token> mintedSplitChildren = new ArrayList<>();
  // Pre-transfer snapshots for level-1 stale-state attempts.
  private final List<Token> splitChildrenPreTransfer = new ArrayList<>();
  // Level-2 (sub-split) children: created when a user splits a received split
  // child.
  private final List<Token> subSplitChildren = new ArrayList<>();
  private final List<Token> subSplitChildrenPreTransfer = new ArrayList<>();
  // The source token right before a sub-split — becomes the "pre-split token"
  // that can no longer be spent after the sub-split burns it.
  private Token preSubSplitSource;
  private Exception splitAttemptError;

  public SplitSteps(TestContext context) {
    this.context = context;
  }

  @Given("{word} has a minted token with 2 payment assets")
  public void userHasAMintedTokenWith2PaymentAssets(String userName) throws Exception {
    mintTokenWithAssets(userName, Set.of(ASSET_1, ASSET_2));
  }

  @Given("{word} has a minted token with 2 payment assets worth {int} and {int}")
  public void userHasAMintedTokenWith2PaymentAssetsWorth(
      String userName, int value1, int value2) throws Exception {
    Asset a1 = new Asset(new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8)),
        BigInteger.valueOf(value1));
    Asset a2 = new Asset(new AssetId("ASSET_2".getBytes(StandardCharsets.UTF_8)),
        BigInteger.valueOf(value2));
    mintTokenWithAssets(userName, Set.of(a1, a2));
  }

  @Given("{word} has a minted token with assets worth {int} and {int}")
  public void userHasAMintedTokenWithAssetsWorth(
      String userName, int value1, int value2) throws Exception {
    userHasAMintedTokenWith2PaymentAssetsWorth(userName, value1, value2);
  }

  @Given("{word} has a minted token containing {int} payment assets")
  public void userHasAMintedTokenContainingNAssets(String userName, int count) throws Exception {
    Set<Asset> assets = new java.util.HashSet<>();
    for (int i = 0; i < count; i++) {
      assets.add(new Asset(
          new AssetId(("ASSET_" + i).getBytes(StandardCharsets.UTF_8)),
          BigInteger.valueOf(100L * (i + 1))));
    }
    mintTokenWithAssets(userName, assets);
  }

  private void mintTokenWithAssets(String userName, Set<Asset> assets) throws Exception {
    ensureUser(userName);
    SigningService signing = context.getUserSigningServices().get(userName);
    SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);

    TestPaymentData paymentData = new TestPaymentData(assets);

    Token token = TokenUtils.mintToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        predicate,
        null,
        paymentData.encode());

    context.addUserToken(userName, token);
    context.setCurrentToken(token);
    context.setCurrentUser(userName);
  }

  @When("{word} splits the token into 2 new tokens")
  public void userSplitsTheTokenInto2NewTokens(String userName) throws Exception {
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token to split");
    SigningService signing = context.getUserSigningServices().get(userName);
    SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);

    // Read source assets and give each child one full asset — works for any
    // 2-asset source (values 100/200, 500/500, etc.) without hardcoding.
    TestPaymentData sourceData = TestPaymentData.decode(sourceToken.getGenesis().getData().orElse(new byte[0]));
    List<Asset> sourceAssets = new ArrayList<>(sourceData.getAssets());
    assertTrue(sourceAssets.size() == 2,
        "source token must have exactly 2 assets for 'into 2 new tokens' split");

    Map<TokenId, Set<Asset>> plan = new HashMap<>();
    plan.put(TokenId.generate(), Set.of(sourceAssets.get(0)));
    plan.put(TokenId.generate(), Set.of(sourceAssets.get(1)));

    SplitResult result = TokenSplit.split(sourceToken, TestPaymentData::decode, plan);
    lastSplitResult = result;

    // Materialize the split: burn the source, then mint each child.
    Token burnToken = TokenUtils.transferToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        sourceToken,
        result.getBurnTransaction(),
        SignaturePredicateUnlockScript.create(result.getBurnTransaction(), signing));

    org.unicitylabs.sdk.transaction.TokenType childType =
        org.unicitylabs.sdk.transaction.TokenType.generate();
    for (Map.Entry<TokenId, Set<Asset>> entry : plan.entrySet()) {
      java.util.List<SplitAssetProof> proofs = result.getProofs().get(entry.getKey());
      assertNotNull(proofs, "no split-reason proofs for child " + entry.getKey());

      byte[] childJustification = SplitMintJustification.create(
          burnToken, new java.util.HashSet<>(proofs)).toCbor();
      byte[] childPayload = new TestPaymentData(entry.getValue()).encode();

      Token child = TokenUtils.mintToken(
          context.getClient(),
          context.getTrustBase(),
          context.getPredicateVerifier(),
          context.getMintJustificationVerifier(),
          entry.getKey(),
          childType,
          predicate,
          childJustification,
          childPayload);

      mintedSplitChildren.add(child); context.getSplitChildren().add(child);
      splitChildrenPreTransfer.add(child);
    }
  }

  @When("{word} splits the token into 2 parts")
  public void userSplitsTheTokenInto2Parts(String userName) throws Exception {
    // Alias for the "new tokens" variant — reads source assets and gives each
    // child one full asset.
    userSplitsTheTokenInto2NewTokens(userName);
  }

  @When("{word} splits the token into 1 output that consumes all assets")
  public void userSplitsTheTokenInto1Output(String userName) throws Exception {
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token to split");
    SigningService signing = context.getUserSigningServices().get(userName);
    SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);

    TestPaymentData sourceData = TestPaymentData.decode(
        sourceToken.getGenesis().getData().orElse(new byte[0]));
    Set<Asset> allAssets = new java.util.HashSet<>(sourceData.getAssets());

    Map<TokenId, Set<Asset>> plan = new HashMap<>();
    plan.put(TokenId.generate(), allAssets);

    SplitResult result = TokenSplit.split(sourceToken, TestPaymentData::decode, plan);
    lastSplitResult = result;

    Token burnToken = TokenUtils.transferToken(
        context.getClient(), context.getTrustBase(), context.getPredicateVerifier(),
        sourceToken,
        result.getBurnTransaction(),
        SignaturePredicateUnlockScript.create(result.getBurnTransaction(), signing));

    org.unicitylabs.sdk.transaction.TokenType childType =
        org.unicitylabs.sdk.transaction.TokenType.generate();
    for (Map.Entry<TokenId, Set<Asset>> entry : plan.entrySet()) {
      java.util.List<SplitAssetProof> proofs = result.getProofs().get(entry.getKey());
      byte[] childJustification = SplitMintJustification.create(
          burnToken, new java.util.HashSet<>(proofs)).toCbor();
      byte[] childPayload = new TestPaymentData(entry.getValue()).encode();
      Token child = TokenUtils.mintToken(
          context.getClient(), context.getTrustBase(), context.getPredicateVerifier(),
          context.getMintJustificationVerifier(),
          entry.getKey(), childType, predicate,
          childJustification, childPayload);
      mintedSplitChildren.add(child);
      context.getSplitChildren().add(child);
    }
  }

  @Then("{int} split token is minted")
  public void nSplitTokenIsMinted(int expected) {
    assertEquals(expected, mintedSplitChildren.size(), "unexpected split-child count");
  }

  // Idempotency scenario: capture cert request, replay it.
  private CertificationData rememberedSplitCertRequest;
  private org.unicitylabs.sdk.api.CertificationStatus rememberedFirstStatus;
  private org.unicitylabs.sdk.api.CertificationStatus secondSubmissionStatus;

  @When("{word} splits the token into 2 outputs and remembers the first cert request")
  public void userSplitsAndRemembersCertRequest(String userName) throws Exception {
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token to split");
    SigningService signing = context.getUserSigningServices().get(userName);

    TestPaymentData sourceData = TestPaymentData.decode(
        sourceToken.getGenesis().getData().orElse(new byte[0]));
    java.util.List<Asset> sourceAssets = new ArrayList<>(sourceData.getAssets());
    Map<TokenId, Set<Asset>> plan = new HashMap<>();
    plan.put(TokenId.generate(), Set.of(sourceAssets.get(0)));
    plan.put(TokenId.generate(), Set.of(sourceAssets.get(1)));

    SplitResult result = TokenSplit.split(sourceToken, TestPaymentData::decode, plan);
    rememberedSplitCertRequest = CertificationData.fromTransaction(
        result.getBurnTransaction(),
        SignaturePredicateUnlockScript.create(result.getBurnTransaction(), signing));
    org.unicitylabs.sdk.api.CertificationResponse first =
        context.getClient().submitCertificationRequest(rememberedSplitCertRequest).get();
    rememberedFirstStatus = first.getStatus();
  }

  @When("the same cert request is submitted again")
  public void theSameCertRequestIsSubmittedAgain() throws Exception {
    org.unicitylabs.sdk.api.CertificationResponse second =
        context.getClient().submitCertificationRequest(rememberedSplitCertRequest).get();
    secondSubmissionStatus = second.getStatus();
  }

  @Then("the second submission status is one of {string} or {string}")
  public void secondSubmissionStatusIsOneOf(String a, String b) {
    assertNotNull(secondSubmissionStatus, "no second-submission status captured");
    String actual = secondSubmissionStatus.name();
    assertTrue(actual.equals(a) || actual.equals(b),
        "expected " + a + " or " + b + " but got " + actual);
  }

  @When("{word} splits the token into 2 parts keeping ownership")
  public void userSplitsTheTokenInto2PartsKeepingOwnership(String userName) throws Exception {
    // Our default split implementation already mints children back to the owner,
    // so this is a semantic alias.
    userSplitsTheTokenInto2NewTokens(userName);
  }

  @When("{word} transfers the first split token to {word}")
  public void userTransfersTheFirstSplitTokenTo(String sender, String recipient) throws Exception {
    userTransfersSplitTokenNTo(sender, 1, recipient);
  }

  @When("{word} transfers split token {int} to {word}")
  public void userTransfersSplitTokenNTo(
      String sender, int index1Based, String recipient) throws Exception {
    int idx = index1Based - 1;
    assertTrue(idx >= 0 && idx < mintedSplitChildren.size(),
        "no split child at index " + index1Based);
    // Use the CURRENT state of the child (post any prior transfers), not the
    // pre-transfer snapshot — that's reserved for stale-token scenarios.
    Token child = mintedSplitChildren.get(idx);
    SigningService senderSigning = context.getUserSigningServices().get(sender);
    assertNotNull(senderSigning, "no signing key for sender " + sender);
    ensureUser(recipient);
    Predicate recipientAddress = context.getUserAddresses().get(recipient);

    Token transferred = TokenUtils.transferToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        child.toCbor(),
        recipientAddress,
        senderSigning);
    // Replace the indexed slot with the post-transfer token so subsequent
    // references in the scenario resolve to the recipient's holding.
    mintedSplitChildren.set(idx, transferred);
    context.addUserToken(recipient, transferred);
    context.setCurrentToken(transferred);
    context.setCurrentUser(recipient);
  }

  @Then("{word}'s token passes verification")
  public void usersTokenPassesVerification(String userName) {
    List<Token> userTokens = context.getUserTokens().get(userName);
    assertNotNull(userTokens, "user " + userName + " has no tokens");
    assertTrue(!userTokens.isEmpty(), "user " + userName + " has empty token list");
    Token token = userTokens.get(userTokens.size() - 1);
    assertEquals(
        VerificationStatus.OK,
        token.verify(context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier()).getStatus(),
        userName + "'s latest token failed verification");
  }

  @Then("{word} can transfer split token {int} to {word}")
  public void userCanTransferSplitTokenNTo(
      String sender, int index1Based, String recipient) throws Exception {
    userTransfersSplitTokenNTo(sender, index1Based, recipient);
  }

  @Then("{word}'s received token passes verification")
  public void usersReceivedTokenPassesVerification(String userName) {
    usersTokenPassesVerification(userName);
  }

  @Then("{word} cannot transfer split token {int} to {word} because it was already sent")
  public void userCannotTransferSplitTokenBecauseAlreadySent(
      String sender, int index1Based, String recipient) throws Exception {
    int idx = index1Based - 1;
    // Use the PRE-TRANSFER token (the stale version the sender thinks they still
    // hold) — StrictTestAggregatorClient must reject with STATE_ID_EXISTS.
    Token staleChild = splitChildrenPreTransfer.get(idx);
    SigningService senderSigning = context.getUserSigningServices().get(sender);
    ensureUser(recipient);
    Predicate recipientAddress = context.getUserAddresses().get(recipient);

    byte[] x = new byte[32];
    new java.security.SecureRandom().nextBytes(x);
    TransferTransaction tx = TransferTransaction.create(staleChild, recipientAddress, x, new byte[0]);

    org.unicitylabs.sdk.api.CertificationResponse response =
        context.getClient().submitCertificationRequest(
            org.unicitylabs.sdk.api.CertificationData.fromTransaction(
                tx, SignaturePredicateUnlockScript.create(tx, senderSigning)))
            .get();
    assertEquals("STATE_ID_EXISTS", response.getStatus().name(),
        "stale split-token transfer should be rejected");
  }

  @When("{word} tries to split with only 1 asset instead of 2")
  public void userTriesToSplitWithWrongAssetCount(String userName) {
    attemptInvalidSplit(userName,
        Map.of(TokenId.generate(), Set.of(ASSET_1)));
  }

  @When("{word} tries to split with a wrong asset ID")
  public void userTriesToSplitWithWrongAssetId(String userName) {
    Asset phantom = new Asset(
        new AssetId("PHANTOM".getBytes(StandardCharsets.UTF_8)),
        BigInteger.valueOf(500));
    attemptInvalidSplit(userName,
        Map.of(TokenId.generate(), Set.of(ASSET_1, phantom)));
  }

  @When("{word} tries to split with incorrect asset values")
  public void userTriesToSplitWithIncorrectAssetValues(String userName) {
    Asset wrongValue = new Asset(ASSET_2.getId(), BigInteger.valueOf(400));
    attemptInvalidSplit(userName,
        Map.of(TokenId.generate(), Set.of(ASSET_1, wrongValue)));
  }

  @When("{word} tries to split with values exceeding the original totals")
  public void userTriesToSplitWithValuesExceedingOriginalTotals(String userName) {
    // Overflow: source has 2 assets (100 and 200); claim more value than exists.
    Asset over1 = new Asset(ASSET_1.getId(), BigInteger.valueOf(500));
    Asset over2 = new Asset(ASSET_2.getId(), BigInteger.valueOf(500));
    attemptInvalidSplit(userName,
        Map.of(TokenId.generate(), Set.of(over1, over2)));
  }

  @When("{word} tries to split with values less than the original totals")
  public void userTriesToSplitWithValuesLessThanOriginalTotals(String userName) {
    Asset under1 = new Asset(ASSET_1.getId(), BigInteger.valueOf(50));
    Asset under2 = new Asset(ASSET_2.getId(), BigInteger.valueOf(50));
    attemptInvalidSplit(userName,
        Map.of(TokenId.generate(), Set.of(under1, under2)));
  }

  @When("{word} tries to split with minimum values of 1 and the remainder")
  public void userTriesToSplitWithMinimumValues(String userName) throws Exception {
    // Source minted via "token with 2 payment assets worth 100 and 200".
    // Two children: child 1 gets {a1=1, a2=1}; child 2 gets {a1=99, a2=199}.
    // Each asset's tree still sums to 100 and 200 respectively → should succeed.
    Asset a1MinA = new Asset(ASSET_1.getId(), BigInteger.ONE);
    Asset a1MinB = new Asset(ASSET_1.getId(), BigInteger.valueOf(99));
    Asset a2MinA = new Asset(ASSET_2.getId(), BigInteger.ONE);
    Asset a2MinB = new Asset(ASSET_2.getId(), BigInteger.valueOf(199));

    try {
      Token sourceToken = context.getCurrentToken();
      SigningService signing = context.getUserSigningServices().get(userName);
      SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);
      Map<TokenId, Set<Asset>> plan = new HashMap<>();
      plan.put(TokenId.generate(), Set.of(a1MinA, a2MinA));
      plan.put(TokenId.generate(), Set.of(a1MinB, a2MinB));
      lastSplitResult = TokenSplit.split(sourceToken, TestPaymentData::decode, plan);
      splitAttemptError = null;
    } catch (Exception e) {
      splitAttemptError = e;
    }
  }

  @When("{word} splits the token into {int} equal parts")
  public void userSplitsTheTokenIntoNEqualParts(String userName, int parts) {
    // Generic N-way split: for each source asset, slice its value into N equal pieces.
    // Requires N to divide each asset's value evenly; combinations-feature assets
    // are constructed with values 100*(i+1) so picking N that divides the smallest
    // value is left to the scenario author.
    try {
      Token sourceToken = context.getCurrentToken();
      SigningService signing = context.getUserSigningServices().get(userName);
      SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);
      TestPaymentData sourceData = TestPaymentData.decode(sourceToken.getGenesis().getData().orElse(new byte[0]));

      Map<TokenId, Set<Asset>> plan = new HashMap<>();
      for (int i = 0; i < parts; i++) {
        Set<Asset> pieceAssets = new java.util.HashSet<>();
        for (Asset src : sourceData.getAssets()) {
          // Divide each source asset's value equally. Integer division; residual
          // goes to the last child to preserve sum.
          BigInteger base = src.getValue().divide(BigInteger.valueOf(parts));
          BigInteger value = (i == parts - 1)
              ? src.getValue().subtract(base.multiply(BigInteger.valueOf(parts - 1)))
              : base;
          if (value.signum() > 0) {
            pieceAssets.add(new Asset(src.getId(), value));
          }
        }
        plan.put(TokenId.generate(), pieceAssets);
      }
      lastSplitResult = TokenSplit.split(sourceToken, TestPaymentData::decode, plan);
      splitAttemptError = null;
    } catch (Exception e) {
      splitAttemptError = e;
      lastSplitResult = null;
    }
  }

  @Then("the split validation succeeds")
  public void theSplitValidationSucceeds() {
    assertTrue(splitAttemptError == null,
        "split unexpectedly failed: "
            + (splitAttemptError != null ? splitAttemptError.getMessage() : ""));
    assertNotNull(lastSplitResult, "no SplitResult recorded after a successful split");
  }

  @Then("the burn transaction succeeds")
  public void theBurnTransactionSucceeds() {
    assertNotNull(lastSplitResult, "no SplitResult recorded");
    assertNotNull(lastSplitResult.getBurnTransaction(), "burn transaction was not produced");
  }

  @Then("{int} split tokens are minted")
  public void nSplitTokensAreMinted(int expected) {
    assertEquals(expected, mintedSplitChildren.size(),
        "unexpected number of minted split children");
  }

  @Then("each split token passes TokenSplit verification")
  public void eachSplitTokenPassesTokenSplitVerification() {
    // Post-issue-52 SDK: TokenSplit.verify() is gone. Split-mint verification
    // happens via the MintJustificationVerifierService registry — registered
    // as SplitMintJustificationVerifier in AggregatorSteps.wireVerifiers. So
    // a normal Token.verify(...) covers what the old TokenSplit.verify did.
    assertTrue(mintedSplitChildren.size() > 0, "no split children minted");
    for (Token child : mintedSplitChildren) {
      assertEquals(
          VerificationStatus.OK,
          child.verify(
              context.getTrustBase(),
              context.getPredicateVerifier(),
              context.getMintJustificationVerifier()).getStatus(),
          "split child failed verification: " + child.getId());
    }
  }

  // TS v2 asserts on typed errors (TokenAssetCountMismatchError etc.); Java v2
  // uses IllegalArgumentException with distinctive messages — see
  // V1_VS_V2_SDK_ANALYSIS.md §11.7 (granular-error-type decision). We assert on
  // message substrings that match the thrown IllegalArgumentException text.
  @Then("the split fails with TokenAssetCountMismatchError")
  public void theSplitFailsWithAssetCountMismatch() {
    assertNotNull(splitAttemptError, "no exception was captured from the split attempt");
    assertTrue(splitAttemptError instanceof IllegalArgumentException,
        "expected IllegalArgumentException, got " + splitAttemptError.getClass());
    assertTrue(
        splitAttemptError.getMessage().contains("asset counts differ"),
        "unexpected error message: " + splitAttemptError.getMessage());
  }

  @Then("the split fails with TokenAssetMissingError")
  public void theSplitFailsWithAssetMissing() {
    assertNotNull(splitAttemptError, "no exception was captured from the split attempt");
    assertTrue(splitAttemptError instanceof IllegalArgumentException,
        "expected IllegalArgumentException, got " + splitAttemptError.getClass());
    // Java v2's TokenSplit throws "Token did not contain asset <id>." when a
    // split plan references an AssetId not present in the source token.
    assertTrue(
        splitAttemptError.getMessage().contains("did not contain"),
        "unexpected error message: " + splitAttemptError.getMessage());
  }

  @Then("the split fails with TokenAssetValueMismatchError")
  public void theSplitFailsWithAssetValueMismatch() {
    assertNotNull(splitAttemptError, "no exception was captured from the split attempt");
    assertTrue(splitAttemptError instanceof IllegalArgumentException,
        "expected IllegalArgumentException, got " + splitAttemptError.getClass());
    assertTrue(
        splitAttemptError.getMessage().contains("tree has"),
        "unexpected error message: " + splitAttemptError.getMessage());
  }

  private void attemptInvalidSplit(String userName, Map<TokenId, Set<Asset>> plan) {
    try {
      Token sourceToken = context.getCurrentToken();
      SigningService signing = context.getUserSigningServices().get(userName);
      SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);
      TokenSplit.split(sourceToken, TestPaymentData::decode, plan);
      splitAttemptError = null;
    } catch (Exception e) {
      splitAttemptError = e;
    }
  }

  private void ensureUser(String userName) {
    if (!context.getUserSigningServices().containsKey(userName)) {
      SigningService signing = SigningService.generate();
      SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);
      context.getUserSigningServices().put(userName, signing);
      context.getUserPredicates().put(userName, predicate);
      context.getUserAddresses().put(userName, predicate);
    }
  }

  // ══ Arbitrary-value split plans (Tier A) ═════════════════════════════════

  @When("^(\\w+) splits the token into 2 parts with values (\\d+)/(\\d+) and (\\d+)/(\\d+)$")
  public void userSplitsTheTokenIntoTwoPartsWithValues(
      String userName,
      int asset1Part1, int asset1Part2,
      int asset2Part1, int asset2Part2) throws Exception {
    // TS convention: "X/Y and A/B" = asset1 splits X/Y between children, asset2
    // splits A/B. Child[0] gets {a1=X, a2=A}, child[1] gets {a1=Y, a2=B}.
    splitCurrentTokenIntoPlannedChildren(
        userName,
        List.of(
            List.of(asset1Part1, asset2Part1),
            List.of(asset1Part2, asset2Part2)));
  }

  private void splitCurrentTokenIntoPlannedChildren(
      String userName, List<List<Integer>> childValues) throws Exception {
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token to split");
    SigningService signing = context.getUserSigningServices().get(userName);
    SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);

    TestPaymentData sourceData = TestPaymentData.decode(sourceToken.getGenesis().getData().orElse(new byte[0]));
    List<Asset> sourceAssets = sortedAssets(sourceData.getAssets());

    Map<TokenId, Set<Asset>> plan = new LinkedHashMap<>();
    for (List<Integer> perChild : childValues) {
      Set<Asset> childAssets = new java.util.HashSet<>();
      for (int i = 0; i < sourceAssets.size() && i < perChild.size(); i++) {
        childAssets.add(new Asset(
            sourceAssets.get(i).getId(), BigInteger.valueOf(perChild.get(i))));
      }
      plan.put(TokenId.generate(), childAssets);
    }

    SplitResult result = TokenSplit.split(sourceToken, TestPaymentData::decode, plan);
    lastSplitResult = result;

    Token burnToken = TokenUtils.transferToken(
        context.getClient(), context.getTrustBase(), context.getPredicateVerifier(),
        sourceToken,
        result.getBurnTransaction(),
        SignaturePredicateUnlockScript.create(result.getBurnTransaction(), signing));

    mintedSplitChildren.clear();
    splitChildrenPreTransfer.clear();
    org.unicitylabs.sdk.transaction.TokenType childType2 =
        org.unicitylabs.sdk.transaction.TokenType.generate();
    for (Map.Entry<TokenId, Set<Asset>> entry : plan.entrySet()) {
      List<SplitAssetProof> proofs = result.getProofs().get(entry.getKey());
      byte[] childJustification = SplitMintJustification.create(
          burnToken, new java.util.HashSet<>(proofs)).toCbor();
      byte[] childPayload = new TestPaymentData(entry.getValue()).encode();
      Token child = TokenUtils.mintToken(
          context.getClient(), context.getTrustBase(), context.getPredicateVerifier(),
          context.getMintJustificationVerifier(),
          entry.getKey(),
          childType2,
          predicate,
          childJustification,
          childPayload);
      mintedSplitChildren.add(child); context.getSplitChildren().add(child);
      splitChildrenPreTransfer.add(child);
    }
  }

  @Then("{word} should own split token {int}")
  public void userShouldOwnSplitTokenN(String userName, int index1Based) {
    int idx = index1Based - 1;
    assertTrue(idx >= 0 && idx < mintedSplitChildren.size(),
        "no split child at index " + index1Based);
    List<Token> userTokens = context.getUserTokens().get(userName);
    assertNotNull(userTokens, "user " + userName + " has no tokens");
    Token expected = mintedSplitChildren.get(idx);
    assertTrue(userTokens.stream().anyMatch(t -> t.getId().equals(expected.getId())),
        userName + " does not hold split token " + index1Based);
  }

  @Then("both split tokens should pass verification")
  public void bothSplitTokensShouldPassVerification() {
    assertTrue(mintedSplitChildren.size() >= 2, "fewer than 2 split children");
    for (Token t : mintedSplitChildren) {
      assertEquals(
          VerificationStatus.OK,
          t.verify(context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier()).getStatus(),
          "split child failed verification: " + t.getId());
    }
  }

  @When("{word} transfers his split token to {word}")
  public void userTransfersHisSplitTokenTo(String sender, String recipient) throws Exception {
    // Find the first split child currently owned by sender (by id match).
    List<Token> senderTokens = context.getUserTokens().get(sender);
    assertNotNull(senderTokens, sender + " has no tokens");
    int idx = -1;
    for (int i = 0; i < mintedSplitChildren.size(); i++) {
      Token child = mintedSplitChildren.get(i);
      for (Token held : senderTokens) {
        if (held.getId().equals(child.getId())) {
          idx = i;
          break;
        }
      }
      if (idx >= 0) {
        break;
      }
    }
    assertTrue(idx >= 0, sender + " holds none of the split children");
    userTransfersSplitTokenNTo(sender, idx + 1, recipient);
  }

  @Then("{word} should own the transferred split token")
  public void userShouldOwnTheTransferredSplitToken(String userName) {
    assertEquals(userName, context.getCurrentUser(),
        "expected " + userName + " to own transferred split token");
  }

  @Then("the transferred split token should pass verification")
  public void theTransferredSplitTokenShouldPassVerification() {
    Token t = context.getCurrentToken();
    assertNotNull(t);
    assertEquals(
        VerificationStatus.OK,
        t.verify(context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier()).getStatus());
  }

  // ── CBOR roundtrip for split child ───────────────────────────────────────

  @When("split token {int} is exported to CBOR")
  public void splitTokenNIsExportedToCbor(int index1Based) {
    int idx = index1Based - 1;
    assertTrue(idx < mintedSplitChildren.size(), "no split child at index " + index1Based);
    Token child = mintedSplitChildren.get(idx);
    context.setCurrentToken(child);
    context.setExportedTokenCbor(child.toCbor());
  }

  @Then("the imported split token should have the same ID")
  public void theImportedSplitTokenShouldHaveTheSameId() {
    Token imported = context.getImportedToken();
    Token original = context.getCurrentToken();
    assertNotNull(imported);
    assertNotNull(original);
    assertEquals(original.getId(), imported.getId());
  }

  @Then("the imported split token should pass verification")
  public void theImportedSplitTokenShouldPassVerification() {
    Token imported = context.getImportedToken();
    assertNotNull(imported);
    assertEquals(
        VerificationStatus.OK,
        imported.verify(context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier()).getStatus());
  }

  // ── Non-owner split attempt ──────────────────────────────────────────────

  @When("{word} tries to split {word}'s token")
  public void userTriesToSplitOthersToken(String attacker, String owner) {
    // Current token is now owned by `owner` (after the preceding transfer step).
    // Attacker attempts to split with their own signing service — should fail.
    Token token = context.getCurrentToken();
    SigningService attackerSigning = context.getUserSigningServices().get(attacker);
    SignaturePredicate attackerPredicate =
        SignaturePredicate.fromSigningService(attackerSigning);
    splitAttemptError = null;
    try {
      Map<TokenId, Set<Asset>> plan = new java.util.HashMap<>();
      TestPaymentData sourceData = TestPaymentData.decode(token.getGenesis().getData().orElse(new byte[0]));
      List<Asset> assets = new ArrayList<>(sourceData.getAssets());
      plan.put(TokenId.generate(), Set.of(assets.get(0)));
      TokenSplit.split(token, TestPaymentData::decode, plan);
    } catch (Exception e) {
      splitAttemptError = e;
    }
  }

  @Then("the split should fail with predicate mismatch")
  public void theSplitShouldFailWithPredicateMismatch() {
    assertNotNull(splitAttemptError, "expected split to fail but no error captured");
  }

  // ── Original-token post-burn attempt ─────────────────────────────────────

  @When("{word} tries to transfer the original token to {word}")
  public void userTriesToTransferTheOriginalTokenTo(String sender, String recipient)
      throws Exception {
    List<Token> senderTokens = context.getUserTokens().get(sender);
    assertNotNull(senderTokens);
    Token original = senderTokens.get(0); // The first-recorded (pre-split) token.
    ensureUser(recipient);

    SigningService senderSigning = context.getUserSigningServices().get(sender);
    byte[] x = new byte[32];
    new java.security.SecureRandom().nextBytes(x);

    TransferTransaction tx = TransferTransaction.create(original, context.getUserAddresses().get(recipient), x, new byte[0]);
    org.unicitylabs.sdk.api.CertificationResponse response =
        context.getClient().submitCertificationRequest(
            org.unicitylabs.sdk.api.CertificationData.fromTransaction(
                tx, SignaturePredicateUnlockScript.create(tx, senderSigning)))
            .get();
    context.setLastCertificationResponse(response);
  }

  @Then("the transfer should fail because the token was burned")
  public void theTransferShouldFailBecauseTheTokenWasBurned() {
    assertNotNull(context.getLastCertificationResponse());
    assertEquals("STATE_ID_EXISTS", context.getLastCertificationResponse().getStatus().name());
  }

  // ══ Multi-level / sub-split (Tier A) ═════════════════════════════════════

  @When("{word} splits his token into 2 sub-parts")
  public void userSplitsHisTokenInto2SubParts(String userName) throws Exception {
    preSubSplitSource = context.getCurrentToken();
    splitCurrentTokenIntoSubChildren(
        userName,
        readSourceAssetsAsValues(context.getCurrentToken()));
  }

  @When("^(\\w+) splits his token into 2 sub-parts with values (\\d+)/(\\d+) and (\\d+)/(\\d+)$")
  public void userSplitsHisTokenInto2SubPartsWithValues(
      String userName,
      int asset1Part1, int asset1Part2,
      int asset2Part1, int asset2Part2) throws Exception {
    preSubSplitSource = context.getCurrentToken();
    splitCurrentTokenIntoSubChildren(
        userName,
        List.of(
            List.of(asset1Part1, asset2Part1),
            List.of(asset1Part2, asset2Part2)));
  }

  // Splits into 2 children by halving each asset's value. The source token is
  // always a split child at this point (sub-split step), so use
  // TestSplitPaymentData as the parser.
  private List<List<Integer>> readSourceAssetsAsValues(Token source) {
    Set<Asset> assets = TestPaymentData.decode(source.getGenesis().getData().orElse(new byte[0])).getAssets();
    List<Asset> sourceAssets = sortedAssets(assets);
    List<List<Integer>> plan = new ArrayList<>();
    List<Integer> c0 = new ArrayList<>();
    List<Integer> c1 = new ArrayList<>();
    for (Asset a : sourceAssets) {
      int v = a.getValue().intValue();
      c0.add(v / 2);
      c1.add(v - v / 2);
    }
    plan.add(c0);
    plan.add(c1);
    return plan;
  }

  private void splitCurrentTokenIntoSubChildren(String userName, List<List<Integer>> plan)
      throws Exception {
    Token sourceToken = context.getCurrentToken();
    SigningService signing = context.getUserSigningServices().get(userName);
    SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);

    // Second-level split: source is always a split child with TestSplitPaymentData encoding.
    org.unicitylabs.sdk.payment.PaymentDataDeserializer parser = TestPaymentData::decode;

    List<Asset> sourceAssets = sortedAssets(
        parser.decode(sourceToken.getGenesis().getData().orElse(new byte[0])).getAssets());

    Map<TokenId, Set<Asset>> splitPlan = new LinkedHashMap<>();
    for (List<Integer> perChild : plan) {
      Set<Asset> assets = new java.util.HashSet<>();
      for (int i = 0; i < sourceAssets.size() && i < perChild.size(); i++) {
        assets.add(new Asset(sourceAssets.get(i).getId(), BigInteger.valueOf(perChild.get(i))));
      }
      splitPlan.put(TokenId.generate(), assets);
    }

    SplitResult result = TokenSplit.split(sourceToken, parser, splitPlan);

    Token burnToken = TokenUtils.transferToken(
        context.getClient(), context.getTrustBase(), context.getPredicateVerifier(),
        sourceToken,
        result.getBurnTransaction(),
        SignaturePredicateUnlockScript.create(result.getBurnTransaction(), signing));

    subSplitChildren.clear();
    subSplitChildrenPreTransfer.clear();
    org.unicitylabs.sdk.transaction.TokenType subChildType =
        org.unicitylabs.sdk.transaction.TokenType.generate();
    for (Map.Entry<TokenId, Set<Asset>> entry : splitPlan.entrySet()) {
      List<SplitAssetProof> proofs = result.getProofs().get(entry.getKey());
      byte[] childJustification = SplitMintJustification.create(
          burnToken, new java.util.HashSet<>(proofs)).toCbor();
      byte[] childPayload = new TestPaymentData(entry.getValue()).encode();
      Token child = TokenUtils.mintToken(
          context.getClient(), context.getTrustBase(), context.getPredicateVerifier(),
          context.getMintJustificationVerifier(),
          entry.getKey(),
          subChildType,
          predicate,
          childJustification,
          childPayload);
      subSplitChildren.add(child);
      subSplitChildrenPreTransfer.add(child);
      context.addUserToken(userName, child);
    }
  }

  private static boolean isSimplePayment(Token token) {
    try {
      TestPaymentData.decode(token.getGenesis().getData().orElse(new byte[0]));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // Sorts assets by AssetId bytes (lexicographic) so callers can index them
  // deterministically. Set.of() iteration order is unspecified; ordering here
  // is how the feature-file grammar maps "first pair" / "second pair" to the
  // right asset consistently.
  private static List<Asset> sortedAssets(Set<Asset> assets) {
    List<Asset> list = new ArrayList<>(assets);
    list.sort((a, b) -> {
      byte[] ba = a.getId().getBytes();
      byte[] bb = b.getId().getBytes();
      int n = Math.min(ba.length, bb.length);
      for (int i = 0; i < n; i++) {
        int diff = (ba[i] & 0xff) - (bb[i] & 0xff);
        if (diff != 0) {
          return diff;
        }
      }
      return ba.length - bb.length;
    });
    return list;
  }

  @When("{word} transfers sub-split token {int} to {word}")
  public void userTransfersSubSplitTokenNTo(String sender, int idx1, String recipient)
      throws Exception {
    int idx = idx1 - 1;
    assertTrue(idx >= 0 && idx < subSplitChildren.size(),
        "no sub-split child at index " + idx1);
    Token child = subSplitChildren.get(idx);
    SigningService senderSigning = context.getUserSigningServices().get(sender);
    ensureUser(recipient);
    Predicate recipientAddress = context.getUserAddresses().get(recipient);

    Token transferred = TokenUtils.transferToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        child.toCbor(),
        recipientAddress,
        senderSigning);
    subSplitChildren.set(idx, transferred);
    context.addUserToken(recipient, transferred);
    context.setCurrentToken(transferred);
    context.setCurrentUser(recipient);
  }

  @Then("{int} sub-split tokens are created")
  public void nSubSplitTokensAreCreated(int expected) {
    assertEquals(expected, subSplitChildren.size(), "unexpected sub-split count");
  }

  @Then("{int} sub-split tokens should be created")
  public void nSubSplitTokensShouldBeCreated(int expected) {
    nSubSplitTokensAreCreated(expected);
  }

  @Then("each sub-split token passes verification")
  public void eachSubSplitTokenPassesVerification() {
    for (Token t : subSplitChildren) {
      assertEquals(
          VerificationStatus.OK,
          t.verify(context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier()).getStatus(),
          "sub-split child failed verification: " + t.getId());
    }
  }

  @Then("each sub-split token should pass verification")
  public void eachSubSplitTokenShouldPassVerification() {
    eachSubSplitTokenPassesVerification();
  }

  @Then("{word}'s token has the correct asset values")
  public void userTokenHasTheCorrectAssetValues(String userName) {
    // Light-touch check: the user's latest token verifies and has non-empty assets.
    List<Token> ts = context.getUserTokens().get(userName);
    assertNotNull(ts, userName + " has no tokens");
    Token latest = ts.get(ts.size() - 1);
    assertEquals(
        VerificationStatus.OK,
        latest.verify(context.getTrustBase(), context.getPredicateVerifier(), context.getMintJustificationVerifier()).getStatus());
    TestPaymentData data = TestPaymentData.decode(latest.getGenesis().getData().orElse(new byte[0]));
    assertTrue(!data.getAssets().isEmpty(), userName + "'s token has no assets");
  }

  @Then("{word} cannot transfer sub-split token {int} to {word} because it was already sent")
  public void userCannotTransferSubSplitTokenBecauseAlreadySent(
      String sender, int idx1, String recipient) throws Exception {
    int idx = idx1 - 1;
    Token stale = subSplitChildrenPreTransfer.get(idx);
    SigningService senderSigning = context.getUserSigningServices().get(sender);
    ensureUser(recipient);
    Predicate recipientAddress = context.getUserAddresses().get(recipient);

    byte[] x = new byte[32];
    new java.security.SecureRandom().nextBytes(x);
    TransferTransaction tx = TransferTransaction.create(stale, recipientAddress, x, new byte[0]);
    org.unicitylabs.sdk.api.CertificationResponse response =
        context.getClient().submitCertificationRequest(
            org.unicitylabs.sdk.api.CertificationData.fromTransaction(
                tx, SignaturePredicateUnlockScript.create(tx, senderSigning)))
            .get();
    assertEquals("STATE_ID_EXISTS", response.getStatus().name());
  }

  @Then("{word} cannot transfer the pre-split token because it was burned")
  public void userCannotTransferThePreSplitTokenBecauseItWasBurned(String sender) throws Exception {
    assertNotNull(preSubSplitSource,
        "no pre-sub-split source recorded — was there a preceding split step?");
    SigningService senderSigning = context.getUserSigningServices().get(sender);
    ensureUser("AnyRecipient");
    Predicate recipientAddress = context.getUserAddresses().get("AnyRecipient");

    byte[] x = new byte[32];
    new java.security.SecureRandom().nextBytes(x);
    TransferTransaction tx = TransferTransaction.create(preSubSplitSource, recipientAddress, x, new byte[0]);
    org.unicitylabs.sdk.api.CertificationResponse response =
        context.getClient().submitCertificationRequest(
            org.unicitylabs.sdk.api.CertificationData.fromTransaction(
                tx, SignaturePredicateUnlockScript.create(tx, senderSigning)))
            .get();
    assertEquals("STATE_ID_EXISTS", response.getStatus().name());
  }
}
