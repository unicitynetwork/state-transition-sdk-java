package org.unicitylabs.sdk.functional.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.payment.SplitMintJustification;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.payment.SplitResult;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end functional test for the token split flow: mint a source token, split it, burn the
 * source, mint the split output token with the resulting justification, and verify the split
 * output through {@link Token#verify}.
 */
public class SplitBuilderTest {

  @Test
  public void buildAndVerifySplitToken() throws Exception {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    RootTrustBase trustBase = aggregatorClient.getTrustBase();
    StateTransitionClient client = new StateTransitionClient(aggregatorClient);
    PredicateVerifierService predicateVerifier = PredicateVerifierService.create(trustBase);

    MintJustificationVerifierService mintJustificationVerifier = new MintJustificationVerifierService();
    mintJustificationVerifier.register(new SplitMintJustificationVerifier(
            trustBase, predicateVerifier, TestPaymentData::decode));

    SigningService signingService = SigningService.generate();
    PayToPublicKeyPredicate ownerPredicate = PayToPublicKeyPredicate.fromSigningService(signingService);

    Set<Asset> assets = Set.of(
            new Asset(new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500)),
            new Asset(new AssetId("ASSET_2".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500))
    );

    Token sourceToken = TokenUtils.mintToken(
            client,
            trustBase,
            predicateVerifier,
            mintJustificationVerifier,
            ownerPredicate,
            null,
            new TestPaymentData(assets).encode()
    );

    TokenId outputTokenId = TokenId.generate();
    SplitResult split = TokenSplit.split(
            sourceToken,
            TestPaymentData::decode,
            Map.of(outputTokenId, assets)
    );

    Token burnToken = TokenUtils.transferToken(
            client,
            trustBase,
            predicateVerifier,
            sourceToken,
            split.getBurnTransaction(),
            PayToPublicKeyPredicateUnlockScript.create(split.getBurnTransaction(), signingService)
    );

    SplitMintJustification justification = SplitMintJustification.create(
            burnToken,
            new LinkedHashSet<>(split.getProofs().get(outputTokenId))
    );

    Token splitToken = TokenUtils.mintToken(
            client,
            trustBase,
            predicateVerifier,
            mintJustificationVerifier,
            outputTokenId,
            TokenType.generate(),
            ownerPredicate,
            justification.toCbor(),
            new TestPaymentData(assets).encode()
    );

    Assertions.assertEquals(
            VerificationStatus.OK,
            splitToken.verify(trustBase, predicateVerifier, mintJustificationVerifier).getStatus()
    );
  }
}
