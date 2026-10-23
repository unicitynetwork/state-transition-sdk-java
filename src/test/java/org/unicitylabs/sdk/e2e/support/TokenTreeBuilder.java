package org.unicitylabs.sdk.e2e.support;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.functional.payment.TestPaymentData;
import org.unicitylabs.sdk.payment.SplitAssetProof;
import org.unicitylabs.sdk.payment.SplitMintJustification;
import org.unicitylabs.sdk.payment.SplitResult;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Builds the 4-level token tree fixture used by {@code token-4level-*.feature}.
 *
 * <p>Layout (mirrors the TS fixture):
 * <pre>
 * L0: Alice mints T0 [A1=1000, A2=2000]
 * L1: Alice splits T0 → T1a[600,1200] + T1b[400,800]
 *     Alice transfers T1a → Bob, T1b → Carol
 * L2: Bob splits T1a → T2a[350,700] + T2b[250,500]
 *     Bob transfers T2a → Carol, T2b → Dave
 * L3: Carol splits T1b → T3a[200,400] + T3b[200,400]
 *     Carol transfers T3a → Dave, T3b → Alice
 * L4: Dave splits T2b → T4a[125,250] + T4b[125,250]
 *     Dave transfers T4a → Alice, T4b → Bob
 * </pre>
 *
 * <p>Cached once per JVM run (~11 aggregator ops) — mirrors TS's module-level
 * cachedTree pattern. Without caching, every tree scenario rebuilds → 1500+
 * aggregator ops on a live aggregator.
 */
public final class TokenTreeBuilder {

  private static volatile TokenTree cached;

  private TokenTreeBuilder() {}

  public static TokenTree buildOrReuse(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      MintJustificationVerifierService mjv) throws Exception {
    TokenTree local = cached;
    if (local != null) {
      return local;
    }
    synchronized (TokenTreeBuilder.class) {
      if (cached != null) {
        return cached;
      }
      cached = build(client, trustBase, predicateVerifier, mjv);
      return cached;
    }
  }

  public static void invalidate() {
    cached = null;
  }

  public static TokenTree build(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      MintJustificationVerifierService mjv) throws Exception {

    TreeUser alice = TreeUser.generate("Alice");
    TreeUser bob = TreeUser.generate("Bob");
    TreeUser carol = TreeUser.generate("Carol");
    TreeUser dave = TreeUser.generate("Dave");

    AssetId assetId1 = new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8));
    AssetId assetId2 = new AssetId("ASSET_2".getBytes(StandardCharsets.UTF_8));

    Map<String, Token> tokens = new LinkedHashMap<>();
    Map<String, TreeUser> owners = new LinkedHashMap<>();

    // L0: Alice mints T0
    TestPaymentData t0Payment = new TestPaymentData(Set.of(
        new Asset(assetId1, BigInteger.valueOf(1000)),
        new Asset(assetId2, BigInteger.valueOf(2000))));
    Token t0 = TokenUtils.mintToken(
        client, trustBase, predicateVerifier, mjv,
        alice.getPredicate(), null, t0Payment.encode());

    // L1
    TokenId t1aId = TokenId.generate();
    TokenId t1bId = TokenId.generate();
    Map<TokenId, Set<Asset>> l1Plan = new LinkedHashMap<>();
    l1Plan.put(t1aId, Set.of(
        new Asset(assetId1, BigInteger.valueOf(600)),
        new Asset(assetId2, BigInteger.valueOf(1200))));
    l1Plan.put(t1bId, Set.of(
        new Asset(assetId1, BigInteger.valueOf(400)),
        new Asset(assetId2, BigInteger.valueOf(800))));
    SplitMaterialized l1 = materializeSplit(
        client, trustBase, predicateVerifier, mjv, t0, alice, l1Plan);
    Token t0Burned = l1.burnedToken;
    Token t1aPre = l1.children.get(t1aId);
    Token t1bPre = l1.children.get(t1bId);
    tokens.put("T0_burned", t0Burned);

    Token t1aBob = transfer(client, trustBase, predicateVerifier, mjv, t1aPre, alice, bob);
    Token t1bCarol = transfer(client, trustBase, predicateVerifier, mjv, t1bPre, alice, carol);

    // L2
    TokenId t2aId = TokenId.generate();
    TokenId t2bId = TokenId.generate();
    Map<TokenId, Set<Asset>> l2Plan = new LinkedHashMap<>();
    l2Plan.put(t2aId, Set.of(
        new Asset(assetId1, BigInteger.valueOf(350)),
        new Asset(assetId2, BigInteger.valueOf(700))));
    l2Plan.put(t2bId, Set.of(
        new Asset(assetId1, BigInteger.valueOf(250)),
        new Asset(assetId2, BigInteger.valueOf(500))));
    SplitMaterialized l2 = materializeSplit(
        client, trustBase, predicateVerifier, mjv, t1aBob, bob, l2Plan);
    Token t1aBurned = l2.burnedToken;
    Token t2aPre = l2.children.get(t2aId);
    Token t2bPre = l2.children.get(t2bId);

    Token t2aCarol = transfer(client, trustBase, predicateVerifier, mjv, t2aPre, bob, carol);
    Token t2bDave = transfer(client, trustBase, predicateVerifier, mjv, t2bPre, bob, dave);

    // L3
    TokenId t3aId = TokenId.generate();
    TokenId t3bId = TokenId.generate();
    Map<TokenId, Set<Asset>> l3Plan = new LinkedHashMap<>();
    l3Plan.put(t3aId, Set.of(
        new Asset(assetId1, BigInteger.valueOf(200)),
        new Asset(assetId2, BigInteger.valueOf(400))));
    l3Plan.put(t3bId, Set.of(
        new Asset(assetId1, BigInteger.valueOf(200)),
        new Asset(assetId2, BigInteger.valueOf(400))));
    SplitMaterialized l3 = materializeSplit(
        client, trustBase, predicateVerifier, mjv, t1bCarol, carol, l3Plan);
    Token t1bBurned = l3.burnedToken;
    Token t3aPre = l3.children.get(t3aId);
    Token t3bPre = l3.children.get(t3bId);

    Token t3aDave = transfer(client, trustBase, predicateVerifier, mjv, t3aPre, carol, dave);
    Token t3bAlice = transfer(client, trustBase, predicateVerifier, mjv, t3bPre, carol, alice);

    // L4
    TokenId t4aId = TokenId.generate();
    TokenId t4bId = TokenId.generate();
    Map<TokenId, Set<Asset>> l4Plan = new LinkedHashMap<>();
    l4Plan.put(t4aId, Set.of(
        new Asset(assetId1, BigInteger.valueOf(125)),
        new Asset(assetId2, BigInteger.valueOf(250))));
    l4Plan.put(t4bId, Set.of(
        new Asset(assetId1, BigInteger.valueOf(125)),
        new Asset(assetId2, BigInteger.valueOf(250))));
    SplitMaterialized l4 = materializeSplit(
        client, trustBase, predicateVerifier, mjv, t2bDave, dave, l4Plan);
    Token t2bBurned = l4.burnedToken;
    Token t4aPre = l4.children.get(t4aId);
    Token t4bPre = l4.children.get(t4bId);

    Token t4aAlice = transfer(client, trustBase, predicateVerifier, mjv, t4aPre, dave, alice);
    Token t4bBob = transfer(client, trustBase, predicateVerifier, mjv, t4bPre, dave, bob);

    tokens.put("T1a_burned", t1aBurned);
    tokens.put("T1a_pre", t1aPre);
    owners.put("T1a_pre", alice);
    tokens.put("T1b_burned", t1bBurned);
    tokens.put("T1b_pre", t1bPre);
    owners.put("T1b_pre", alice);
    tokens.put("T2a_carol", t2aCarol);
    owners.put("T2a_carol", carol);
    tokens.put("T2a_pre", t2aPre);
    owners.put("T2a_pre", bob);
    tokens.put("T2b_burned", t2bBurned);
    tokens.put("T2b_pre", t2bPre);
    owners.put("T2b_pre", bob);
    tokens.put("T3a_dave", t3aDave);
    owners.put("T3a_dave", dave);
    tokens.put("T3a_pre", t3aPre);
    owners.put("T3a_pre", carol);
    tokens.put("T3b_alice", t3bAlice);
    owners.put("T3b_alice", alice);
    tokens.put("T3b_pre", t3bPre);
    owners.put("T3b_pre", carol);
    tokens.put("T4a_alice", t4aAlice);
    owners.put("T4a_alice", alice);
    tokens.put("T4a_pre", t4aPre);
    owners.put("T4a_pre", dave);
    tokens.put("T4b_bob", t4bBob);
    owners.put("T4b_bob", bob);
    tokens.put("T4b_pre", t4bPre);
    owners.put("T4b_pre", dave);

    Map<String, TreeUser> users = new LinkedHashMap<>();
    users.put("Alice", alice);
    users.put("Bob", bob);
    users.put("Carol", carol);
    users.put("Dave", dave);

    return new TokenTree(
        client, trustBase, predicateVerifier, mjv,
        assetId1, assetId2,
        users, tokens, owners);
  }

  private static Token transfer(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      MintJustificationVerifierService mjv,
      Token source,
      TreeUser sender,
      TreeUser recipient) throws Exception {
    return TokenUtils.transferToken(
        client, trustBase, predicateVerifier, mjv,
        source.toCbor(),
        recipient.getPredicate(),
        sender.getSigningService());
  }

  /**
   * Performs a split end-to-end against the new SDK API:
   * <ol>
   * <li>{@link TokenSplit#split} (3-arg form: token, parser, plan)</li>
   * <li>burn the source via the prebuilt {@link SplitResult#getBurnTransaction}</li>
   * <li>mint each child with {@code justification = SplitMintJustification.toCbor}
   *     and {@code data = TestPaymentData.encode}</li>
   * </ol>
   */
  private static SplitMaterialized materializeSplit(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      MintJustificationVerifierService mjv,
      Token source,
      TreeUser owner,
      Map<TokenId, Set<Asset>> plan) throws Exception {
    SplitResult splitResult = TokenSplit.split(source, TestPaymentData::decode, plan);

    Token burned = TokenUtils.transferToken(
        client, trustBase, predicateVerifier,
        source,
        splitResult.getBurnTransaction(),
        SignaturePredicateUnlockScript.create(
            splitResult.getBurnTransaction(), owner.getSigningService()));

    Map<TokenId, Token> children = new HashMap<>();
    TokenType childType = TokenType.generate();
    for (Map.Entry<TokenId, Set<Asset>> entry : plan.entrySet()) {
      List<SplitAssetProof> proofs = splitResult.getProofs().get(entry.getKey());
      Set<SplitAssetProof> proofSet = new HashSet<>(proofs);
      byte[] justification = SplitMintJustification.create(burned, proofSet).toCbor();
      byte[] data = new TestPaymentData(entry.getValue()).encode();
      Token child = TokenUtils.mintToken(
          client, trustBase, predicateVerifier, mjv,
          entry.getKey(),
          childType,
          owner.getPredicate(),
          justification,
          data);
      children.put(entry.getKey(), child);
    }
    return new SplitMaterialized(burned, children);
  }

  private static final class SplitMaterialized {
    final Token burnedToken;
    final Map<TokenId, Token> children;

    SplitMaterialized(Token burnedToken, Map<TokenId, Token> children) {
      this.burnedToken = burnedToken;
      this.children = children;
    }
  }
}
