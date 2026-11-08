package org.unicitylabs.sdk.functional.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.payment.SplitMintJustification;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.payment.SplitResult;
import org.unicitylabs.sdk.payment.SplitToken;
import org.unicitylabs.sdk.payment.SplitTokenRequest;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.payment.asset.PaymentAssetCollection;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.StateMask;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifierService;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.unicitylabs.sdk.utils.ExpiresAt;

/**
 * End-to-end functional test for the token split flow: mint a source token, split it, burn the
 * source with the manifest attached, mint the split outputs with the resulting justifications,
 * and verify each output through {@link Token#verify}.
 */
public class SplitBuilderTest {

  @Test
  public void buildAndVerifySplitToken() throws Exception {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    TrustBaseHistoryView trustBase = aggregatorClient.getTrustBase();
    StateTransitionClient client = new StateTransitionClient(aggregatorClient);
    PredicateVerifierService predicateVerifier = PredicateVerifierService.create();

    MintJustificationVerifierService mintJustificationVerifier = new MintJustificationVerifierService();
    mintJustificationVerifier.register(new SplitMintJustificationVerifier(TestPaymentData::decode));
    VerificationContext context = new VerificationContext(trustBase, predicateVerifier,
            mintJustificationVerifier, new TokenIssuanceVerifierService(false));

    SigningService signingService = SigningService.generate();
    SignaturePredicate ownerPredicate = SignaturePredicate.fromSigningService(signingService);

    Asset asset1 = new Asset(new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));
    Asset asset2 = new Asset(new AssetId("ASSET_2".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));

    Token sourceToken = TokenUtils.mintToken(
            client,
            context,
            ownerPredicate,
            new TestPaymentData(PaymentAssetCollection.create(asset1, asset2)).encode()
    );

    List<SplitTokenRequest> requests = List.of(
            SplitTokenRequest.create(ownerPredicate,
                    new TestPaymentData(PaymentAssetCollection.create(asset1))),
            SplitTokenRequest.create(ownerPredicate,
                    new TestPaymentData(PaymentAssetCollection.create(asset2)))
    );

    SplitResult split = TokenSplit.split(sourceToken, TestPaymentData::decode, requests, StateMask.generate(), ExpiresAt.expiresAt());

    Token burnToken = TokenUtils.transferToken(
            client,
            context,
            sourceToken,
            split.getBurnTransaction(),
            SignaturePredicateUnlockScript.create(split.getBurnTransaction(), signingService)
    );

    List<Token> mintedTokens = new ArrayList<>();
    for (SplitToken splitToken : split.getTokens()) {
      SplitMintJustification justification = SplitMintJustification.create(
              burnToken, splitToken.getProofs());

      Token minted = TokenUtils.mintToken(
              client,
              context,
              splitToken.getRecipient(),
              splitToken.getPaymentData().encode(),
              splitToken.getNetworkId(),
              splitToken.getTokenType(),
              splitToken.getSalt(),
              justification.toCbor()
      );

      Assertions.assertEquals(
              VerificationStatus.OK,
              Token.fromCbor(minted.toCbor())
                      .verify(context)
                      .getStatus()
      );
      mintedTokens.add(minted);
    }

    // Split the first output again: verifying the second-generation token walks the whole
    // provenance chain (split output -> burned split token -> burned source token) iteratively.
    Token firstOutput = mintedTokens.get(0);
    List<SplitTokenRequest> secondRequests = List.of(
            SplitTokenRequest.create(ownerPredicate,
                    new TestPaymentData(PaymentAssetCollection.create(asset1)))
    );

    SplitResult secondSplit = TokenSplit.split(firstOutput, TestPaymentData::decode, secondRequests, StateMask.generate(), ExpiresAt.expiresAt());

    Token secondBurnToken = TokenUtils.transferToken(
            client,
            context,
            firstOutput,
            secondSplit.getBurnTransaction(),
            SignaturePredicateUnlockScript.create(secondSplit.getBurnTransaction(), signingService)
    );

    SplitToken secondSplitToken = secondSplit.getTokens().get(0);
    SplitMintJustification secondJustification = SplitMintJustification.create(
            secondBurnToken, secondSplitToken.getProofs());

    Token secondMinted = TokenUtils.mintToken(
            client,
            context,
            secondSplitToken.getRecipient(),
            secondSplitToken.getPaymentData().encode(),
            secondSplitToken.getNetworkId(),
            secondSplitToken.getTokenType(),
            secondSplitToken.getSalt(),
            secondJustification.toCbor()
    );

    Assertions.assertEquals(
            VerificationStatus.OK,
            secondMinted.verify(context).getStatus()
    );
  }

  @Test
  public void rebuildsByteIdenticalBurnTransactionFromSuppliedStateMask() throws Exception {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    TrustBaseHistoryView trustBase = aggregatorClient.getTrustBase();
    StateTransitionClient client = new StateTransitionClient(aggregatorClient);
    PredicateVerifierService predicateVerifier = PredicateVerifierService.create();

    MintJustificationVerifierService mintJustificationVerifier = new MintJustificationVerifierService();
    mintJustificationVerifier.register(new SplitMintJustificationVerifier(TestPaymentData::decode));
    VerificationContext context = new VerificationContext(trustBase, predicateVerifier,
            mintJustificationVerifier, new TokenIssuanceVerifierService(false));

    SigningService signingService = SigningService.generate();
    SignaturePredicate ownerPredicate = SignaturePredicate.fromSigningService(signingService);

    Asset asset = new Asset(new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));

    Token token = TokenUtils.mintToken(
            client,
            context,
            ownerPredicate,
            new TestPaymentData(PaymentAssetCollection.create(asset)).encode()
    );

    // Identical requests across all calls; only the burn mask determines reproducibility.
    List<SplitTokenRequest> requests = List.of(
            SplitTokenRequest.create(ownerPredicate,
                    new TestPaymentData(PaymentAssetCollection.create(asset)))
    );
    StateMask burnStateMask = StateMask.generate();

    SplitResult first = TokenSplit.split(token, TestPaymentData::decode, requests, burnStateMask, ExpiresAt.expiresAt());
    SplitResult second = TokenSplit.split(token, TestPaymentData::decode, requests, burnStateMask, ExpiresAt.expiresAt());
    SplitResult defaulted = TokenSplit.split(token, TestPaymentData::decode, requests, StateMask.generate(), ExpiresAt.expiresAt());

    byte[] firstBurn = first.getBurnTransaction().toCbor();
    Assertions.assertArrayEquals(firstBurn, second.getBurnTransaction().toCbor());
    // The default stays random - omitting the mask must not become deterministic.
    Assertions.assertFalse(Arrays.equals(firstBurn, defaulted.getBurnTransaction().toCbor()));

    // A mask of the wrong length is a caller bug - the StateMask type rejects it at construction.
    Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> StateMask.fromBytes(new byte[StateMask.MIN_LENGTH - 1]));
  }
}
