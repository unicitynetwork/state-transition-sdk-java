package org.unicitylabs.sdk.e2e.support;

import java.util.Collections;
import java.util.Map;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.functional.payment.TestPaymentData;
import org.unicitylabs.sdk.payment.PaymentDataDeserializer;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;

/**
 * Immutable container for the 4-level token tree fixture. See {@link
 * TokenTreeBuilder} for the build-order narrative.
 */
public final class TokenTree {

  private final StateTransitionClient client;
  private final RootTrustBase trustBase;
  private final PredicateVerifierService predicateVerifier;
  private final MintJustificationVerifierService mintJustificationVerifier;
  private final AssetId assetId1;
  private final AssetId assetId2;
  private final Map<String, TreeUser> usersByName;
  private final Map<String, Token> tokensByName;
  private final Map<String, TreeUser> ownersByTokenName;

  TokenTree(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      MintJustificationVerifierService mintJustificationVerifier,
      AssetId assetId1,
      AssetId assetId2,
      Map<String, TreeUser> usersByName,
      Map<String, Token> tokensByName,
      Map<String, TreeUser> ownersByTokenName) {
    this.client = client;
    this.trustBase = trustBase;
    this.predicateVerifier = predicateVerifier;
    this.mintJustificationVerifier = mintJustificationVerifier;
    this.assetId1 = assetId1;
    this.assetId2 = assetId2;
    this.usersByName = Collections.unmodifiableMap(usersByName);
    this.tokensByName = Collections.unmodifiableMap(tokensByName);
    this.ownersByTokenName = Collections.unmodifiableMap(ownersByTokenName);
  }

  public StateTransitionClient getClient() {
    return client;
  }

  public RootTrustBase getTrustBase() {
    return trustBase;
  }

  public PredicateVerifierService getPredicateVerifier() {
    return predicateVerifier;
  }

  public MintJustificationVerifierService getMintJustificationVerifier() {
    return mintJustificationVerifier;
  }

  public AssetId getAssetId1() {
    return assetId1;
  }

  public AssetId getAssetId2() {
    return assetId2;
  }

  public Map<String, TreeUser> getUsersByName() {
    return usersByName;
  }

  public Map<String, Token> getTokensByName() {
    return tokensByName;
  }

  public Map<String, TreeUser> getOwnersByTokenName() {
    return ownersByTokenName;
  }

  public TreeUser requireUser(String name) {
    TreeUser u = usersByName.get(name);
    if (u == null) {
      throw new IllegalArgumentException("tree has no user named " + name);
    }
    return u;
  }

  public Token requireToken(String name) {
    Token t = tokensByName.get(name);
    if (t == null) {
      throw new IllegalArgumentException("tree has no token named " + name);
    }
    return t;
  }

  public TreeUser requireOwner(String tokenName) {
    TreeUser owner = ownersByTokenName.get(tokenName);
    if (owner == null) {
      throw new IllegalArgumentException("tree has no recorded owner for " + tokenName);
    }
    return owner;
  }

  /**
   * Picks the right payment-data deserializer for the named token. Post-issue-52
   * SDK refactor: payment data is now uniformly {@link TestPaymentData} — the
   * split-reason that used to ride inside the data blob has moved into a
   * separate {@code justification} field on the mint transaction.
   */
  public PaymentDataDeserializer parserFor(String tokenName) {
    return TestPaymentData::decode;
  }
}
