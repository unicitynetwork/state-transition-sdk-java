package org.unicitylabs.sdk.functional.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.payment.SplitTokenRequest;
import org.unicitylabs.sdk.payment.TokenAssetCountMismatchException;
import org.unicitylabs.sdk.payment.TokenAssetMissingException;
import org.unicitylabs.sdk.payment.TokenAssetValueMismatchException;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.payment.asset.PaymentAssetCollection;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.StateMask;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifierService;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.utils.TokenUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.unicitylabs.sdk.utils.ExpiresAt;

/**
 * Unit tests for the precondition branches of {@link TokenSplit#split}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TokenSplitTest {

  private Asset asset1;
  private Asset asset2;
  private Token sourceToken;

  @BeforeAll
  public void setupFixture() throws Exception {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    TrustBaseHistoryView trustBase = aggregatorClient.getTrustBase();
    StateTransitionClient client = new StateTransitionClient(aggregatorClient);
    PredicateVerifierService predicateVerifier = PredicateVerifierService.create();

    MintJustificationVerifierService mintJustificationVerifier = new MintJustificationVerifierService();
    mintJustificationVerifier.register(new SplitMintJustificationVerifier(TestPaymentData::decode));
    VerificationContext context = new VerificationContext(trustBase, predicateVerifier,
            mintJustificationVerifier, new TokenIssuanceVerifierService(false));

    SignaturePredicate ownerPredicate = SignaturePredicate.fromSigningService(SigningService.generate());

    this.asset1 = new Asset(new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));
    this.asset2 = new Asset(new AssetId("ASSET_2".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));

    this.sourceToken = TokenUtils.mintToken(
            client,
            context,
            ownerPredicate,
            new TestPaymentData(PaymentAssetCollection.create(this.asset1, this.asset2)).encode()
    );
  }

  @Test
  public void splitFailsWhenAssetCountsDiffer() {
    TokenAssetCountMismatchException exception = Assertions.assertThrows(
            TokenAssetCountMismatchException.class,
            () -> TokenSplit.split(this.sourceToken, TestPaymentData::decode, List.of(SplitTokenRequest.create(
                            SignaturePredicate.fromSigningService(SigningService.generate()),
                            new TestPaymentData(PaymentAssetCollection.create(this.asset1))
                    )), StateMask.generate(), ExpiresAt.expiresAt())
    );
    Assertions.assertEquals("Token and split tokens asset counts differ.", exception.getMessage());
  }

  @Test
  public void splitFailsWhenAssetIsMissingFromSource() {
    Asset unknownAsset = new Asset(
            new AssetId("ASSET_3".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(400));

    TokenAssetMissingException exception = Assertions.assertThrows(
            TokenAssetMissingException.class,
            () -> TokenSplit.split(this.sourceToken, TestPaymentData::decode, List.of(SplitTokenRequest.create(
                            SignaturePredicate.fromSigningService(SigningService.generate()),
                            new TestPaymentData(PaymentAssetCollection.create(this.asset1, unknownAsset))
                    )), StateMask.generate(), ExpiresAt.expiresAt())
    );
    Assertions.assertEquals(
            String.format("Token did not contain asset %s.", unknownAsset.getId()),
            exception.getMessage());
  }

  @Test
  public void splitFailsWhenAssetTreeAmountIsLess() {
    TokenAssetValueMismatchException exception = Assertions.assertThrows(
            TokenAssetValueMismatchException.class,
            () -> TokenSplit.split(this.sourceToken, TestPaymentData::decode, List.of(SplitTokenRequest.create(
                            SignaturePredicate.fromSigningService(SigningService.generate()),
                            new TestPaymentData(PaymentAssetCollection.create(
                                    this.asset1, new Asset(this.asset2.getId(), BigInteger.valueOf(400))))
                    )), StateMask.generate(), ExpiresAt.expiresAt())
    );
    Assertions.assertEquals("Token contained 500 AssetId{bytes=41535345545f32} assets, but tree has 400",
            exception.getMessage());
  }

  @Test
  public void splitFailsWhenAssetTreeAmountIsMore() {
    TokenAssetValueMismatchException exception = Assertions.assertThrows(
            TokenAssetValueMismatchException.class,
            () -> TokenSplit.split(this.sourceToken, TestPaymentData::decode, List.of(SplitTokenRequest.create(
                            SignaturePredicate.fromSigningService(SigningService.generate()),
                            new TestPaymentData(PaymentAssetCollection.create(
                                    this.asset1, new Asset(this.asset2.getId(), BigInteger.valueOf(1500))))
                    )), StateMask.generate(), ExpiresAt.expiresAt())
    );
    Assertions.assertEquals("Token contained 500 AssetId{bytes=41535345545f32} assets, but tree has 1500",
            exception.getMessage());
  }
}
