package org.unicitylabs.sdk.functional.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.payment.PaymentData;
import org.unicitylabs.sdk.payment.SplitMintJustification;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.payment.SplitPayloadMismatchException;
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
import org.unicitylabs.sdk.utils.ExpiresAt;
import org.unicitylabs.sdk.utils.TokenUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Split output payloads are validated against the split's decoder and frozen. */
public class SplitPayloadFreezeTest {

  private static final AssetId ID = new AssetId("ASSET".getBytes(StandardCharsets.UTF_8));

  private StateTransitionClient client;
  private VerificationContext context;
  private SigningService signingService;
  private SignaturePredicate owner;

  @BeforeEach
  public void setUp() {
    TestAggregatorClient aggregator = TestAggregatorClient.create();
    this.client = new StateTransitionClient(aggregator);
    MintJustificationVerifierService justifications = new MintJustificationVerifierService();
    justifications.register(new SplitMintJustificationVerifier(WrappedPaymentData::decode));
    this.context = new VerificationContext(aggregator.getTrustBase(),
            PredicateVerifierService.create(), justifications,
            new TokenIssuanceVerifierService(false));
    this.signingService = SigningService.generate();
    this.owner = SignaturePredicate.fromSigningService(this.signingService);
  }

  private static PaymentAssetCollection assets(long value) {
    return PaymentAssetCollection.create(new Asset(ID, BigInteger.valueOf(value)));
  }

  private Token mintSource(long value) throws Exception {
    return TokenUtils.mintToken(this.client, this.context, this.owner,
            new WrappedPaymentData(assets(value)).encode());
  }

  private Token burnAndMint(Token source, SplitResult split, SplitToken output) throws Exception {
    Token burned = TokenUtils.transferToken(this.client, this.context, source,
            split.getBurnTransaction(),
            SignaturePredicateUnlockScript.create(split.getBurnTransaction(),
                    this.signingService));
    return TokenUtils.mintToken(this.client, this.context, output.getRecipient(),
            output.getPaymentData().encode(), output.getNetworkId(), output.getTokenType(),
            output.getSalt(), SplitMintJustification.create(burned, output.getProofs()).toCbor());
  }

  private static SplitResult split(Token source, PaymentData... outputs) throws Exception {
    List<SplitTokenRequest> requests = new ArrayList<>();
    SignaturePredicate recipient = SignaturePredicate.fromSigningService(SigningService.generate());
    for (PaymentData output : outputs) {
      requests.add(SplitTokenRequest.create(recipient, output));
    }
    return TokenSplit.split(source, WrappedPaymentData::decode, requests, StateMask.generate(),
            ExpiresAt.expiresAt());
  }

  @Test
  public void wrappedTenSplitsIntoThreeAndSevenAndAncestryVerifies() throws Exception {
    Token source = mintSource(10);
    SplitResult split = TokenSplit.split(source, WrappedPaymentData::decode, List.of(
            SplitTokenRequest.create(this.owner, new WrappedPaymentData(assets(3))),
            SplitTokenRequest.create(this.owner, new WrappedPaymentData(assets(7)))),
            StateMask.generate(), ExpiresAt.expiresAt());

    Token burned = TokenUtils.transferToken(this.client, this.context, source,
            split.getBurnTransaction(),
            SignaturePredicateUnlockScript.create(split.getBurnTransaction(),
                    this.signingService));
    List<Token> minted = new ArrayList<>();
    for (SplitToken output : split.getTokens()) {
      Token token = TokenUtils.mintToken(this.client, this.context, output.getRecipient(),
              output.getPaymentData().encode(), output.getNetworkId(), output.getTokenType(),
              output.getSalt(),
              SplitMintJustification.create(burned, output.getProofs()).toCbor());
      Assertions.assertEquals(VerificationStatus.OK,
              Token.fromCbor(token.toCbor()).verify(this.context).getStatus());
      minted.add(token);
    }

    // Split the 7-child again; verifying the grandchild walks the whole ancestry.
    Token child = minted.get(1);
    SplitResult second = TokenSplit.split(child, WrappedPaymentData::decode, List.of(
            SplitTokenRequest.create(this.owner, new WrappedPaymentData(assets(2))),
            SplitTokenRequest.create(this.owner, new WrappedPaymentData(assets(5)))),
            StateMask.generate(), ExpiresAt.expiresAt());
    for (SplitToken output : second.getTokens()) {
      Token grandchild = burnAndMint(child, second, output);
      Assertions.assertEquals(VerificationStatus.OK,
              grandchild.verify(this.context).getStatus());
    }
  }

  @Test
  public void rejectsPayloadThatDecodesToDifferentAmount() throws Exception {
    Token source = mintSource(10);
    PaymentData skewed = new PaymentData() {
      @Override
      public PaymentAssetCollection getAssets() {
        return assets(3);
      }

      @Override
      public byte[] encode() {
        return new WrappedPaymentData(assets(4)).encode();
      }
    };
    Assertions.assertThrows(SplitPayloadMismatchException.class,
            () -> split(source, skewed, new WrappedPaymentData(assets(7))));
  }

  @Test
  public void rejectsBareOutputUnderWrappedDecoder() throws Exception {
    Token source = mintSource(10);
    PaymentData bare = new TestPaymentData(assets(3));
    Assertions.assertThrows(RuntimeException.class,
            () -> split(source, bare, new WrappedPaymentData(assets(7))));
  }

  @Test
  public void rejectsDecoderThatSkewsAmounts() throws Exception {
    Token source = mintSource(10);
    // Source decodes honestly; the outputs' bytes decode with a skewed amount.
    AtomicInteger calls = new AtomicInteger();
    List<SplitTokenRequest> requests = List.of(
            SplitTokenRequest.create(this.owner, new WrappedPaymentData(assets(3))),
            SplitTokenRequest.create(this.owner, new WrappedPaymentData(assets(7))));
    Assertions.assertThrows(SplitPayloadMismatchException.class,
            () -> TokenSplit.split(source, bytes -> {
              PaymentData real = WrappedPaymentData.decode(bytes);
              if (calls.getAndIncrement() == 0) {
                return real; // source token
              }
              return new TestPaymentData(PaymentAssetCollection.create(new Asset(ID,
                      real.getAssets().get(ID).getValue().add(BigInteger.ONE))));
            }, requests, StateMask.generate(), ExpiresAt.expiresAt()));
  }

  @Test
  public void rejectsMismatchInEveryEntryPoint() throws Exception {
    Token source = mintSource(10);
    List<SplitTokenRequest> requests = List.of(SplitTokenRequest.create(this.owner,
            new TestPaymentData(assets(10))));
    StateMask mask = StateMask.generate();
    Assertions.assertThrows(RuntimeException.class,
            () -> TokenSplit.split(source, WrappedPaymentData::decode, requests));
    Assertions.assertThrows(RuntimeException.class,
            () -> TokenSplit.split(source, WrappedPaymentData::decode, requests, mask));
    Assertions.assertThrows(RuntimeException.class,
            () -> TokenSplit.split(source, WrappedPaymentData::decode, requests,
                    ExpiresAt.expiresAt()));
    Assertions.assertThrows(RuntimeException.class,
            () -> TokenSplit.split(source, WrappedPaymentData::decode, requests, mask,
                    ExpiresAt.expiresAt()));
  }

  @Test
  public void assetsWithSameIdButDifferentValueAreNotEqual() throws Exception {
    Token source = mintSource(10);
    // Asset.equals compares ids only; the check must still catch a value difference.
    PaymentData lying = new PaymentData() {
      @Override
      public PaymentAssetCollection getAssets() {
        return assets(10);
      }

      @Override
      public byte[] encode() {
        return new WrappedPaymentData(assets(9)).encode();
      }
    };
    Assertions.assertThrows(SplitPayloadMismatchException.class, () -> split(source, lying));
  }

  @Test
  public void mutablePayloadCannotChangeMintedBytes() throws Exception {
    Token source = mintSource(10);
    MutablePayload mutable = new MutablePayload(3);
    SplitTokenRequest first = SplitTokenRequest.create(this.owner, mutable);
    SplitTokenRequest second = SplitTokenRequest.create(this.owner,
            new WrappedPaymentData(assets(7)));
    SplitResult split = TokenSplit.split(source, WrappedPaymentData::decode,
            List.of(first, second), StateMask.generate(), ExpiresAt.expiresAt());

    SplitToken output = split.getTokens().get(0);
    byte[] committed = output.getPaymentData().encode();
    Assertions.assertArrayEquals(new WrappedPaymentData(assets(3)).encode(), committed);

    mutable.value = 4; // mutate after the burn commitment exists
    Assertions.assertArrayEquals(committed, output.getPaymentData().encode());

    // Returned arrays are copies.
    byte[] copy = output.getPaymentData().encode();
    copy[copy.length - 1] ^= 0x01;
    Assertions.assertArrayEquals(committed, output.getPaymentData().encode());

    Token minted = burnAndMint(source, split, output);
    Assertions.assertEquals(VerificationStatus.OK, minted.verify(this.context).getStatus());
  }

  @Test
  public void nondeterministicEncoderCannotChangeMintedBytes() throws Exception {
    Token source = mintSource(10);
    // Each encode() call returns a different, but valid, encoding: first 3, then 4, ...
    AtomicInteger calls = new AtomicInteger();
    Supplier<byte[]> encoder = () -> new WrappedPaymentData(assets(3 + calls.getAndIncrement()))
            .encode();
    PaymentData flaky = new PaymentData() {
      @Override
      public PaymentAssetCollection getAssets() {
        return assets(3);
      }

      @Override
      public byte[] encode() {
        return encoder.get();
      }
    };
    SplitResult split = split(source, flaky, new WrappedPaymentData(assets(7)));
    SplitToken output = split.getTokens().get(0);

    Assertions.assertArrayEquals(new WrappedPaymentData(assets(3)).encode(),
            output.getPaymentData().encode());
    Assertions.assertEquals(1, calls.get(), "payload must be encoded exactly once");
    Assertions.assertEquals(VerificationStatus.OK,
            burnAndMint(source, split, output).verify(this.context).getStatus());
  }

  private static final class MutablePayload implements PaymentData {
    long value;

    MutablePayload(long value) {
      this.value = value;
    }

    @Override
    public PaymentAssetCollection getAssets() {
      return assets(this.value);
    }

    @Override
    public byte[] encode() {
      return new WrappedPaymentData(assets(this.value)).encode();
    }
  }
}
