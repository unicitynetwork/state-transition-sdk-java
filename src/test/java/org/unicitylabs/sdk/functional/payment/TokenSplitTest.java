package org.unicitylabs.sdk.functional.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.utils.TokenUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for the precondition (IAE) branches of {@link TokenSplit#split}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TokenSplitTest {

  private Asset asset1;
  private Asset asset2;
  private Token sourceToken;

  @BeforeAll
  public void setupFixture() throws Exception {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    RootTrustBase trustBase = aggregatorClient.getTrustBase();
    StateTransitionClient client = new StateTransitionClient(aggregatorClient);
    PredicateVerifierService predicateVerifier = PredicateVerifierService.create();

    MintJustificationVerifierService mintJustificationVerifier = new MintJustificationVerifierService();
    mintJustificationVerifier.register(new SplitMintJustificationVerifier(
            trustBase, predicateVerifier, TestPaymentData::decode));

    SigningService signingService = SigningService.generate();
    SignaturePredicate ownerPredicate = SignaturePredicate.fromSigningService(signingService);

    this.asset1 = new Asset(new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));
    this.asset2 = new Asset(new AssetId("ASSET_2".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));

    this.sourceToken = TokenUtils.mintToken(
            client,
            trustBase,
            predicateVerifier,
            mintJustificationVerifier,
            ownerPredicate,
            null,
            new TestPaymentData(Set.of(this.asset1, this.asset2)).encode()
    );
  }

  @Test
  public void splitFailsWhenAssetCountsDiffer() {
    IllegalArgumentException exception = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> TokenSplit.split(
                    this.sourceToken,
                    TestPaymentData::decode,
                    Map.of(TokenId.generate(), Set.of(this.asset1))
            )
    );
    Assertions.assertEquals("Token and split tokens asset counts differ.", exception.getMessage());
  }

  @Test
  public void splitFailsWhenAssetTreeAmountIsLess() {
    IllegalArgumentException exception = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> TokenSplit.split(
                    this.sourceToken,
                    TestPaymentData::decode,
                    Map.of(
                            TokenId.generate(),
                            Set.of(this.asset1, new Asset(this.asset2.getId(), BigInteger.valueOf(400)))
                    )
            )
    );
    Assertions.assertEquals("Token contained 500 AssetId{bytes=41535345545f32} assets, but tree has 400",
            exception.getMessage());
  }

  @Test
  public void splitFailsWhenAssetTreeAmountIsMore() {
    IllegalArgumentException exception = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> TokenSplit.split(
                    this.sourceToken,
                    TestPaymentData::decode,
                    Map.of(
                            TokenId.generate(),
                            Set.of(this.asset1, new Asset(this.asset2.getId(), BigInteger.valueOf(1500)))
                    )
            )
    );
    Assertions.assertEquals("Token contained 500 AssetId{bytes=41535345545f32} assets, but tree has 1500",
            exception.getMessage());
  }
}
