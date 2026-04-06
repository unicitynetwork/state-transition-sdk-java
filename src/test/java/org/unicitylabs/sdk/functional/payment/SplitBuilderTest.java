package org.unicitylabs.sdk.functional.payment;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.payment.SplitReason;
import org.unicitylabs.sdk.payment.SplitReasonProof;
import org.unicitylabs.sdk.payment.SplitResult;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Address;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Functional tests for minting and splitting tokens with proof verification.
 */
public class SplitBuilderTest {

  /**
   * Verifies end-to-end mint, split, burn and validation flow.
   *
   * @throws Exception when async client interactions fail
   */
  @Test
  public void testMintAndSplitToken() throws Exception {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    RootTrustBase trustBase = aggregatorClient.getTrustBase();
    StateTransitionClient client = new StateTransitionClient(aggregatorClient);
    PredicateVerifierService predicateVerifier = PredicateVerifierService.create(trustBase);

    SigningService signingService = SigningService.generate();
    PayToPublicKeyPredicate predicate = PayToPublicKeyPredicate.fromSigningService(signingService);

    Asset asset1 = new Asset(new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));
    Asset asset2 = new Asset(new AssetId("ASSET_2".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));

    Set<Asset> assets = Set.of(asset1, asset2);
    TestPaymentData paymentData = new TestPaymentData(assets);

    Token token = TokenUtils.mintToken(
        client,
        trustBase,
        predicateVerifier,
        Address.fromPredicate(predicate),
        paymentData.encode()
    );

    IllegalArgumentException exception = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> TokenSplit.split(
            token,
            predicate,
            TestPaymentData::decode,
            Map.of(TokenId.generate(), Set.of(asset1))
        )
    );

    Assertions.assertEquals("Token and split tokens asset counts differ.", exception.getMessage());

    exception = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> TokenSplit.split(
            token,
            predicate,
            TestPaymentData::decode,
            Map.of(TokenId.generate(), Set.of(asset1, new Asset(asset2.getId(), BigInteger.valueOf(400))))
        )
    );

    Assertions.assertEquals("Token contained 500 AssetId{bytes=41535345545f32} assets, but tree has 400",
        exception.getMessage());

    exception = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> TokenSplit.split(
            token,
            predicate,
            TestPaymentData::decode,
            Map.of(TokenId.generate(), Set.of(asset1, new Asset(asset2.getId(), BigInteger.valueOf(1500))))
        )
    );

    Assertions.assertEquals("Token contained 500 AssetId{bytes=41535345545f32} assets, but tree has 1500",
        exception.getMessage());

    Map<TokenId, Set<Asset>> splitTokens = Map.of(
        TokenId.generate(), Set.of(asset1),
        TokenId.generate(), Set.of(asset2)
    );

    SplitResult result = TokenSplit.split(token, predicate, TestPaymentData::decode, splitTokens);

    Token burnToken = TokenUtils.transferToken(
        client,
        trustBase,
        predicateVerifier,
        token,
        result.getBurnTransaction(),
        PayToPublicKeyPredicateUnlockScript.create(result.getBurnTransaction(), signingService)
    );

    for (Entry<TokenId, Set<Asset>> entry : splitTokens.entrySet()) {
      List<SplitReasonProof> proofs = result.getProofs().get(entry.getKey());
      Assertions.assertNotNull(proofs);

      Token splitToken = TokenUtils.mintToken(
          client,
          trustBase,
          predicateVerifier,
          entry.getKey(),
          Address.fromPredicate(predicate),
          new TestSplitPaymentData(
              entry.getValue(),
              SplitReason.create(
                  burnToken,
                  proofs
              )
          ).encode()
      );

      Assertions.assertEquals(VerificationStatus.OK, splitToken.verify(trustBase, predicateVerifier).getStatus());
      Assertions.assertEquals(VerificationStatus.OK,
          TokenSplit.verify(
              Token.fromCbor(splitToken.toCbor()),
              TestSplitPaymentData::decode,
              trustBase,
              predicateVerifier
          ).getStatus());
    }
  }
}
