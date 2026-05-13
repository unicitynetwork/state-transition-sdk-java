package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.When;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.e2e.support.NametagRegistry;
import org.unicitylabs.sdk.functional.payment.TestPaymentData;
import org.unicitylabs.sdk.payment.SplitAssetProof;
import org.unicitylabs.sdk.payment.SplitMintJustification;
import org.unicitylabs.sdk.payment.SplitResult;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.unicityid.UnicityIdToken;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Steps for {@code token-nametag-split.feature}: cross of split-mint flow with
 * pubkey/nametag addressing for the recipients of each split child.
 *
 * <p>Mirrors TS {@code mixed-addressing.steps.ts} for the split-related "Alice
 * splits a 2-asset token and sends child 1 to Bob via <method>" / "...sends
 * child 1 to Bob via X, and child 2 to Carol via Y" / "after the split, Bob
 * splits his child again and sends grandchild 1 to Carol via <method>"
 * scenarios.
 */
public class NametagSplitSteps {

  private final TestContext context;

  // Tracks the most recent set of split children so the "Bob splits his child"
  // step can pick child 1 by index.
  private List<Token> latestSplitChildren = new ArrayList<>();

  public NametagSplitSteps(TestContext context) {
    this.context = context;
  }

  @When("Alice splits a 2-asset token and sends child 1 to Bob via {word}")
  public void aliceSplitsAndSendsChild1ToBob(String method) throws Exception {
    splitAndSend("Alice", new String[] {"Bob", null},
        new String[] {method, null});
  }

  @When("Alice splits a 2-asset token, sends child 1 to Bob via {word}, and child 2 to Carol via {word}")
  public void aliceSplitsAndSendsChild1ToBobChild2ToCarol(String bobMethod, String carolMethod)
      throws Exception {
    splitAndSend("Alice", new String[] {"Bob", "Carol"},
        new String[] {bobMethod, carolMethod});
  }

  @When("after the split, Bob splits his child again and sends grandchild 1 to Carol via {word}")
  public void bobSplitsHisChildAndSendsGrandchild1ToCarol(String method) throws Exception {
    // Bob's child from the previous step is the 0th element of the child list
    // we transferred to him.
    List<Token> bobsTokens = context.getUserTokens().get("Bob");
    assertNotNull(bobsTokens, "Bob has no tokens — the previous split must run first");
    assertTrue(!bobsTokens.isEmpty(), "Bob's token list is empty");
    Token bobsChild = bobsTokens.get(bobsTokens.size() - 1);

    // Bob's child has 1 asset (he received one of Alice's two split halves).
    // Split it into two halves of value floor(v/2) and v - floor(v/2).
    byte[] dataBytes = bobsChild.getGenesis().getData().orElse(new byte[0]);
    Set<Asset> bobsAssets = TestPaymentData.decode(dataBytes).getAssets();
    assertTrue(bobsAssets.size() == 1,
        "Bob's child should have exactly 1 asset; has " + bobsAssets.size());
    Asset single = bobsAssets.iterator().next();
    BigInteger half = single.getValue().shiftRight(1);
    Asset halfA = new Asset(single.getId(), half);
    Asset halfB = new Asset(single.getId(), single.getValue().subtract(half));

    Map<TokenId, Set<Asset>> plan = new HashMap<>();
    TokenId grandId1 = TokenId.generate();
    TokenId grandId2 = TokenId.generate();
    plan.put(grandId1, Set.of(halfA));
    plan.put(grandId2, Set.of(halfB));

    SigningService bobSigning = context.getUserSigningServices().get("Bob");
    SignaturePredicate bobPredicate =
        SignaturePredicate.fromSigningService(bobSigning);

    List<Token> grandchildren = materialiseSplit(bobsChild, plan, bobSigning, bobPredicate);

    // Transfer grandchild 1 (the one bound to grandId1) to Carol via <method>.
    Token grandchild1 = pickByTokenId(grandchildren, grandId1);
    Predicate carolRecipient = resolveRecipient("Carol", method);
    Token transferred = TokenUtils.transferToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        grandchild1.toCbor(),
        carolRecipient,
        bobSigning);
    context.setCurrentToken(transferred);
    context.setCurrentUser("Bob");
    context.addUserToken("Carol", transferred);
  }

  // ── Internal helpers ─────────────────────────────────────────────────────

  /**
   * Mints Alice a 2-asset token and splits it into 2 children (one per asset),
   * then transfers each child to its declared recipient via the corresponding
   * addressing method. {@code recipients[i]==null} skips delivery for child i.
   */
  private void splitAndSend(String sender, String[] recipients, String[] methods)
      throws Exception {
    ensureUser(sender);
    SigningService senderSigning = context.getUserSigningServices().get(sender);
    SignaturePredicate senderPredicate =
        SignaturePredicate.fromSigningService(senderSigning);

    // Mint Alice's parent: 2 distinct random asset IDs of value 100 / 200.
    Asset asset1 = new Asset(new AssetId(randomBytes(10)), BigInteger.valueOf(100));
    Asset asset2 = new Asset(new AssetId(randomBytes(10)), BigInteger.valueOf(200));
    Set<Asset> sourceAssets = Set.of(asset1, asset2);
    Token parent = TokenUtils.mintToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        senderPredicate,
        null,
        new TestPaymentData(sourceAssets).encode());

    // Split into 2 children — one asset each.
    Map<TokenId, Set<Asset>> plan = new HashMap<>();
    TokenId childId1 = TokenId.generate();
    TokenId childId2 = TokenId.generate();
    plan.put(childId1, Set.of(asset1));
    plan.put(childId2, Set.of(asset2));

    List<Token> children = materialiseSplit(parent, plan, senderSigning, senderPredicate);
    latestSplitChildren = new ArrayList<>(children);
    Token child1 = pickByTokenId(children, childId1);
    Token child2 = pickByTokenId(children, childId2);

    // Deliver each child to its declared recipient. The sender is Alice
    // (current owner of the children); the recipient predicate is resolved
    // via pubkey/nametag.
    if (recipients[0] != null) {
      Token transferred = transferChild(child1, recipients[0], methods[0], senderSigning);
      context.setCurrentToken(transferred);
      context.setCurrentUser(sender);
      context.addUserToken(recipients[0], transferred);
    }
    if (recipients[1] != null) {
      Token transferred = transferChild(child2, recipients[1], methods[1], senderSigning);
      // For two-recipient flow we don't reset currentToken — the assertions
      // ("the current token verifies", etc.) target the most recent transfer.
      // The TS feature's mixed-recipient outline doesn't assert anything past
      // the When step, so leaving this as the latest transfer is safe.
      context.setCurrentToken(transferred);
      context.addUserToken(recipients[1], transferred);
    }
  }

  /** Performs a split: burns the source, mints children with split-mint justifications. */
  private List<Token> materialiseSplit(
      Token source, Map<TokenId, Set<Asset>> plan,
      SigningService sourceSigning, SignaturePredicate sourcePredicate) throws Exception {
    SplitResult result = TokenSplit.split(source, TestPaymentData::decode, plan);
    Token burnToken = TokenUtils.transferToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        source,
        result.getBurnTransaction(),
        SignaturePredicateUnlockScript.create(result.getBurnTransaction(), sourceSigning));

    TokenType childType = TokenType.generate();
    List<Token> minted = new ArrayList<>();
    for (Map.Entry<TokenId, Set<Asset>> entry : plan.entrySet()) {
      List<SplitAssetProof> proofs = result.getProofs().get(entry.getKey());
      assertNotNull(proofs, "no proofs for child " + entry.getKey());
      byte[] childJustification = SplitMintJustification.create(
          burnToken, new HashSet<>(proofs)).toCbor();
      byte[] childPayload = new TestPaymentData(entry.getValue()).encode();
      Token child = TokenUtils.mintToken(
          context.getClient(),
          context.getTrustBase(),
          context.getPredicateVerifier(),
          context.getMintJustificationVerifier(),
          entry.getKey(),
          childType,
          sourcePredicate,
          childJustification,
          childPayload);
      minted.add(child);
      context.getSplitChildren().add(child);
    }
    return minted;
  }

  private Token transferChild(Token child, String recipientName, String method,
      SigningService senderSigning) throws Exception {
    Predicate recipientPredicate = resolveRecipient(recipientName, method);
    return TokenUtils.transferToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        child.toCbor(),
        recipientPredicate,
        senderSigning);
  }

  private Predicate resolveRecipient(String name, String method) throws Exception {
    ensureUser(name);
    if ("pubkey".equals(method)) {
      return context.getUserPredicates().get(name);
    }
    if ("nametag".equals(method)) {
      UnicityIdToken nametag = context.getUserNametags().get(name);
      // Background may not have registered a nametag for ad-hoc users — auto-register if missing.
      if (nametag == null) {
        SignaturePredicate userPredicate =
            (SignaturePredicate) context.getUserPredicates().get(name);
        nametag = NametagRegistry.registerNametag(
            context.getClient(),
            context.getTrustBase(),
            context.getPredicateVerifier(),
            userPredicate,
            "auto-" + name + "-" + System.nanoTime(),
            "bdd/test");
        context.getUserNametags().put(name, nametag);
      }
      return nametag.getGenesis().getTargetPredicate();
    }
    throw new IllegalArgumentException("Unsupported addressing method: " + method);
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

  private static Token pickByTokenId(List<Token> tokens, TokenId target) {
    for (Token t : tokens) {
      if (t.getId().equals(target)) {
        return t;
      }
    }
    throw new IllegalStateException("no token with id " + target);
  }

  private static byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    new java.security.SecureRandom().nextBytes(b);
    return b;
  }
}
