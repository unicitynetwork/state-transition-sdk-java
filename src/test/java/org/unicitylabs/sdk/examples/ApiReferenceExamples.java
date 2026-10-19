package org.unicitylabs.sdk.examples;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import okhttp3.OkHttpClient;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.BatchProofException;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationRejectedException;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.InclusionProofWaitException;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;
import org.unicitylabs.sdk.api.NonInclusionOutcome;
import org.unicitylabs.sdk.api.NonInclusionProof;
import org.unicitylabs.sdk.api.NonInclusionProofException;
import org.unicitylabs.sdk.api.NonInclusionSnapshot;
import org.unicitylabs.sdk.api.NonInclusionContext;
import org.unicitylabs.sdk.api.NonInclusionVerifier;
import org.unicitylabs.sdk.api.RequestOptions;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.WaitOptions;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcNetworkException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcResponseException;
import org.unicitylabs.sdk.consolidation.ConsolidationException;
import org.unicitylabs.sdk.consolidation.ConsolidationResult;
import org.unicitylabs.sdk.consolidation.ShardRouting;
import org.unicitylabs.sdk.consolidation.TokenProofClient;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.payment.PaymentData;
import org.unicitylabs.sdk.payment.PaymentDataDeserializer;
import org.unicitylabs.sdk.payment.SplitMintJustification;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.payment.SplitResult;
import org.unicitylabs.sdk.payment.SplitToken;
import org.unicitylabs.sdk.payment.SplitTokenRequest;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.payment.asset.PaymentAssetCollection;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.CertifiedMintTransaction;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.StateMask;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifierService;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.InclusionProofUtils;
import org.unicitylabs.sdk.util.verification.VerificationException;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * The snippets of docs/api-reference.md outside the waiting chapter. They are compiled with the
 * tests, and {@code ApiReferenceExamplesTest} runs the token flow against the in-memory aggregator.
 * {@code ApiReferenceSnippetsTest} checks that every snippet of the document is a copy of lines
 * here.
 */
final class ApiReferenceExamples {

  private ApiReferenceExamples() {
  }

  /** Payment data of an application: the SDK only needs the asset collection. */
  static final class Payment implements PaymentData {

    private final PaymentAssetCollection assets;

    Payment(PaymentAssetCollection assets) {
      this.assets = assets;
    }

    static Payment decode(byte[] bytes) {
      return new Payment(PaymentAssetCollection.fromCbor(bytes));
    }

    @Override
    public PaymentAssetCollection getAssets() {
      return assets;
    }

    @Override
    public byte[] encode() {
      return assets.toCbor();
    }
  }

  /** A checkpoint the application obtained independently of the proof and of the server. */
  static final class Checkpoint {

    private final int networkId;
    private final long requiredRootRound;

    Checkpoint(int networkId, long requiredRootRound) {
      this.networkId = networkId;
      this.requiredRootRound = requiredRootRound;
    }

    int networkId() {
      return networkId;
    }

    long requiredRootRound() {
      return requiredRootRound;
    }
  }

  // 1. Setup

  static StateTransitionClient connect(String apiKey) {
    var aggregator = new JsonRpcAggregatorClient(
        "https://gateway.testnet2.unicity.network", apiKey);
    return new StateTransitionClient(aggregator);
  }

  static RootTrustBase loadTrustBase() throws IOException {
    return RootTrustBase.fromJson(Files.readString(Path.of("bft-trustbase.testnet2.json")));
  }

  static JsonRpcAggregatorClient withTimeouts(String url, String apiKey) {
    var http = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    return new JsonRpcAggregatorClient(url, apiKey, false, http);
  }

  static CompletableFuture<InclusionProofResponse> lookupWithTimeout(
      StateTransitionClient client, StateId stateId) {
    return client.getInclusionProof(stateId,
        RequestOptions.none().withCallTimeout(Duration.ofSeconds(5)));
  }

  static VerificationContext createContext(RootTrustBase trustBase) {
    var predicateVerifier = PredicateVerifierService.create();
    return new VerificationContext(trustBase, predicateVerifier,
        new MintJustificationVerifierService(), new TokenIssuanceVerifierService(false));
  }

  // 2. Keys, signing and predicates

  static SignaturePredicate newOwner(byte[] storedPrivateKey) {
    var signingService = new SigningService(storedPrivateKey);
    return SignaturePredicate.fromSigningService(signingService);
  }

  static byte[] newPrivateKey() {
    return SigningService.generatePrivateKey();
  }

  static Predicate recipientFromPublicKey(byte[] recipientPublicKey) {
    return SignaturePredicate.create(recipientPublicKey);
  }

  // 3. Minting

  static Token mint(StateTransitionClient client, VerificationContext context,
      Predicate owner, byte[] payload) throws Exception {
    MintTransaction mint = MintTransaction.builder(context.getTrustBase().getNetworkId(), owner)
        .tokenType(new TokenType("my-token-type".getBytes(StandardCharsets.UTF_8)))
        .data(payload)
        .build();

    CertificationData data = CertificationData.fromMintTransaction(mint);
    CertificationResponse response = client.submitCertificationRequest(data).get();
    if (response.getStatus() != CertificationStatus.SUCCESS) {
      throw new IllegalStateException("Mint rejected: " + response.getStatus());
    }

    InclusionProof proof = InclusionProofUtils.waitInclusionProof(
        client, context.getTrustBase(), context.getPredicateVerifier(), mint).get();
    CertifiedMintTransaction genesis = mint.toCertifiedTransaction(
        context.getTrustBase(), context.getPredicateVerifier(), proof);
    return Token.mint(genesis, context);
  }

  static PaymentAssetCollection assets() {
    var coins = new Asset(new AssetId("COIN".getBytes(StandardCharsets.UTF_8)),
        BigInteger.valueOf(500));
    var points = new Asset(new AssetId("POINT".getBytes(StandardCharsets.UTF_8)),
        BigInteger.valueOf(20));
    return PaymentAssetCollection.create(coins, points);
  }

  static Token mintPayment(StateTransitionClient client, VerificationContext context,
      Predicate owner, PaymentAssetCollection assets) throws Exception {
    byte[] payload = new Payment(assets).encode();
    return mint(client, context, owner, payload);
  }

  // 4. Transfers

  static Token transfer(StateTransitionClient client, VerificationContext context, Token token,
      SigningService sender, Predicate recipient) throws Exception {
    TransferTransaction transfer = TransferTransaction.create(
        token, recipient, StateMask.generate(), null);
    return submitTransfer(client, context, token, sender, transfer);
  }

  static Token submitTransfer(StateTransitionClient client, VerificationContext context,
      Token token, SigningService sender, TransferTransaction transfer) throws Exception {
    CertificationData data = CertificationData.fromTransaction(
        transfer, SignaturePredicateUnlockScript.create(transfer, sender));

    InclusionProof proof = client.submitAndWait(transfer, data, 2, context.getTrustBase(),
        context.getPredicateVerifier(), WaitOptions.defaults()).get();
    return token.transfer(transfer.toCertifiedTransaction(
        context.getTrustBase(), context.getPredicateVerifier(), proof), context);
  }

  static boolean isMine(Token received, SignaturePredicate me) {
    return received.getLatestTransaction().getRecipient().equals(EncodedPredicate.fromPredicate(me));
  }

  // 5. Verification

  static void requireValid(Token token, VerificationContext context) {
    VerificationResult<VerificationStatus> result = token.verify(context);
    if (result.getStatus() != VerificationStatus.OK) {
      throw new IllegalArgumentException("Token rejected: " + result);
    }
  }

  static String transferCount(Token token) {
    return token.getId() + " has " + token.getTransactions().size() + " transfers";
  }

  // 6. Serialization

  static Token store(Token token) {
    byte[] bytes = token.toCbor();
    String hex = HexConverter.encode(bytes);
    return Token.fromCbor(HexConverter.decode(hex));
  }

  static String trustBaseJson(RootTrustBase trustBase) {
    String json = trustBase.toJson();
    return RootTrustBase.fromJson(json).getNetworkId().toString();
  }

  // 8. Errors

  static String handle(CompletableFuture<InclusionProof> wait) throws InterruptedException {
    try {
      wait.get();
      return "proof";
    } catch (ExecutionException e) {
      Throwable failure = e.getCause();
      if (failure instanceof InclusionProofWaitException
          && ((InclusionProofWaitException) failure).isResubmittable()) {
        return "submit the same request again";
      }
      if (failure instanceof TimeoutException) {
        return "no proof in time: the state may still be certified later";
      }
      if (failure instanceof VerificationException) {
        return "proof did not verify: do not accept";
      }
      if (failure instanceof CertificationRejectedException) {
        return "rejected: " + ((CertificationRejectedException) failure).getStatus();
      }
      return "service or transport failure: " + failure;
    } catch (CancellationException e) {
      return "cancelled";
    }
  }

  static boolean checked(Throwable failure) {
    Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
    return cause instanceof JsonRpcNetworkException || cause instanceof JsonRpcResponseException
        || cause instanceof BatchProofException || cause instanceof NonInclusionProofException
        || cause instanceof ConsolidationException;
  }

  // 9. Advanced

  static NonInclusionSnapshot requireAbsent(RootTrustBase trustBase, NonInclusionProof proof,
      Checkpoint checkpoint) {
    // `checkpoint` is obtained independently of the proof and of the server response.
    NonInclusionOutcome outcome =
        NonInclusionVerifier.verify(trustBase, proof, NonInclusionContext.unsharded());
    if (!outcome.isAbsent()) {
      throw new IllegalStateException("no verified absence");
    }
    NonInclusionSnapshot snapshot = outcome.getSnapshot();
    if (snapshot.getNetworkId().getId() != checkpoint.networkId()
        || Long.compareUnsigned(snapshot.getRootRound(), checkpoint.requiredRootRound()) < 0) {
      throw new IllegalStateException("snapshot does not meet application policy");
    }
    // Absence at `snapshot` only; any spend or finality decision needs its own policy.
    return snapshot;
  }

  static CompletableFuture<ConsolidationResult> consolidate(StateTransitionClient client,
      VerificationContext context, Token token) {
    var routing = ShardRouting.single(client.getAggregatorClient());
    return new TokenProofClient(routing, context).consolidate(token);
  }

  // Splitting a payment token

  static List<SplitTokenRequest> splitRequests(Predicate first, Predicate second) {
    var coins = new Asset(new AssetId("COIN".getBytes(StandardCharsets.UTF_8)),
        BigInteger.valueOf(500));
    var points = new Asset(new AssetId("POINT".getBytes(StandardCharsets.UTF_8)),
        BigInteger.valueOf(20));
    List<SplitTokenRequest> requests = List.of(
        SplitTokenRequest.create(first, new Payment(PaymentAssetCollection.create(coins))),
        SplitTokenRequest.create(second, new Payment(PaymentAssetCollection.create(points))));
    return requests;
  }

  static List<Token> split(StateTransitionClient client, VerificationContext context,
      Token source, SigningService owner, List<SplitTokenRequest> requests) throws Exception {
    SplitResult split = TokenSplit.split(source, Payment::decode, requests);

    Token burned = submitTransfer(client, context, source, owner, split.getBurnTransaction());
    List<Token> outputs = new ArrayList<>();
    for (SplitToken output : split.getTokens()) {
      byte[] justification = SplitMintJustification.create(burned, output.getProofs()).toCbor();
      outputs.add(mintSplitOutput(client, context, output, justification));
    }
    return outputs;
  }

  static PaymentDataDeserializer paymentDecoder() {
    return Payment::decode;
  }

  static VerificationContext splitContext(RootTrustBase trustBase) {
    var justifications = new MintJustificationVerifierService()
        .register(new SplitMintJustificationVerifier(Payment::decode));
    return new VerificationContext(trustBase, PredicateVerifierService.create(), justifications,
        new TokenIssuanceVerifierService(false));
  }

  private static Token mintSplitOutput(StateTransitionClient client, VerificationContext context,
      SplitToken output, byte[] justification) throws Exception {
    MintTransaction mint = MintTransaction.builder(output.getNetworkId(), output.getRecipient())
        .tokenType(output.getTokenType())
        .salt(output.getSalt())
        .data(output.getPaymentData().encode())
        .justification(justification)
        .build();
    InclusionProof proof = client.submitAndWait(mint, CertificationData.fromMintTransaction(mint),
        2, context.getTrustBase(), context.getPredicateVerifier(), WaitOptions.defaults()).get();
    return Token.mint(mint.toCertifiedTransaction(
        context.getTrustBase(), context.getPredicateVerifier(), proof), context);
  }
}
