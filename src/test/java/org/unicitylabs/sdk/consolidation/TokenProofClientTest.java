package org.unicitylabs.sdk.consolidation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.BatchAggregatorClient;
import org.unicitylabs.sdk.api.BatchRequestOptions;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.InclusionProofBatch;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.CertifiedTransferTransaction;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifierService;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * The explicit consolidation of a real token against a single (unsharded) aggregator whose answers
 * come from the same tree the token was minted in.
 */
class TokenProofClientTest {

  private static final SigningService ALICE = SigningService.generate();
  private static final SigningService BOB = SigningService.generate();
  private static final SigningService CAROL = SigningService.generate();

  private TestAggregatorClient aggregator;
  private StateTransitionClient client;
  private VerificationContext context;

  /** A batch capable view of the test aggregator that records what it is asked. */
  private final class Batch implements BatchAggregatorClient {
    final List<Integer> sizes = new CopyOnWriteArrayList<>();
    final List<BatchRequestOptions> options = new CopyOnWriteArrayList<>();
    final AtomicInteger singleCalls = new AtomicInteger();
    final AtomicInteger submits = new AtomicInteger();
    CompletableFuture<InclusionProofBatch> hang;
    Runnable onCall;

    @Override
    public CompletableFuture<InclusionProofBatch> getInclusionProofs(List<StateId> stateIds,
        BatchRequestOptions requestOptions) {
      sizes.add(stateIds.size());
      options.add(requestOptions);
      if (onCall != null) {
        onCall.run();
      }
      if (hang != null) {
        return hang;
      }
      List<InclusionProofResponse> proofs = new ArrayList<>();
      for (StateId id : stateIds) {
        proofs.add(aggregator.getInclusionProof(id).join());
      }
      return CompletableFuture.completedFuture(new InclusionProofBatch(proofs, null, 1, null));
    }

    @Override
    public CompletableFuture<CertificationResponse> submitCertificationRequest(
        CertificationData certificationData) {
      submits.incrementAndGet();
      throw new AssertionError("a consolidation never submits");
    }

    @Override
    public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
      singleCalls.incrementAndGet();
      throw new AssertionError("a batch is never emulated by single requests");
    }

    @Override
    public CompletableFuture<Long> getLatestBlockNumber() {
      throw new AssertionError("not needed");
    }
  }

  /** Runs work at once until it is held, then queues it until released: a saturated CPU executor. */
  private static final class GateExecutor implements java.util.concurrent.Executor {
    private final java.util.Queue<Runnable> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    volatile boolean hold;

    @Override
    public void execute(Runnable task) {
      if (hold) {
        queue.add(task);
      } else {
        task.run();
      }
    }

    int queued() {
      return queue.size();
    }

    void release() {
      hold = false;
      Runnable task;
      while ((task = queue.poll()) != null) {
        task.run();
      }
    }
  }

  @BeforeEach
  void setUp() {
    aggregator = TestAggregatorClient.create();
    client = new StateTransitionClient(aggregator);
    context = new VerificationContext(aggregator.getTrustBase(), PredicateVerifierService.create(),
        new MintJustificationVerifierService(), new TokenIssuanceVerifierService(false));
  }

  private Token threeProofToken() throws Exception {
    Token minted = TokenUtils.mintToken(client, context,
        SignaturePredicate.create(ALICE.getPublicKey()));
    Token bob = TokenUtils.transferToken(client, context, minted.toCbor(),
        SignaturePredicate.create(BOB.getPublicKey()), ALICE);
    return TokenUtils.transferToken(client, context, bob.toCbor(),
        SignaturePredicate.create(CAROL.getPublicKey()), BOB);
  }

  private static Set<String> seals(Token token) {
    Set<String> seals = new HashSet<>();
    seals.add(HexConverter.encode(
        token.getGenesis().getInclusionProof().getUnicityCertificate().getUnicitySeal().toCbor()));
    for (CertifiedTransferTransaction transfer : token.getTransactions()) {
      seals.add(HexConverter.encode(
          transfer.getInclusionProof().getUnicityCertificate().getUnicitySeal().toCbor()));
    }
    return seals;
  }

  private ConsolidationResult consolidate(Token token, Batch batch, ConsolidationOptions options)
      throws Exception {
    return new TokenProofClient(ShardRouting.single(batch), context)
        .consolidate(token, options.withExecutor(Runnable::run)).get(30, TimeUnit.SECONDS);
  }

  @Test
  void aTokenWithSeveralSealsIsRebuiltUnderOne() throws Exception {
    Token token = threeProofToken();
    byte[] before = token.toCbor();
    assertEquals(3, seals(token).size());

    Batch batch = new Batch();
    ConsolidationResult result = consolidate(token, batch, ConsolidationOptions.defaults());

    assertEquals(ConsolidationResult.Status.CONSOLIDATED, result.getStatus());
    Token consolidated = result.getToken();
    assertEquals(1, seals(consolidated).size());
    assertEquals(VerificationStatus.OK, consolidated.verify(context).getStatus());
    assertEquals(token.getId(), consolidated.getId());
    assertEquals(token.getTransactions().size(), consolidated.getTransactions().size());

    // one batch request, all three states, the latest view, no wait
    assertEquals(List.of(3), batch.sizes);
    assertNull(batch.options.get(0).getStateRoot());
    assertNull(batch.options.get(0).getWaitMillis());
    assertEquals(0, batch.singleCalls.get());
    assertEquals(0, batch.submits.get());

    ProofSummary summary = result.getSummary();
    assertFalse(summary.isAlreadyConsolidated());
    assertEquals(3, summary.getOriginalSeals());
    assertEquals(1, summary.getConsolidatedSeals());
    assertEquals(3, summary.getOuterProofs());
    assertEquals(1, summary.getRequests());
    assertEquals(1, summary.getShards());
    assertEquals("1", summary.getMasterRoundDecimal());
    assertEquals(3, summary.getEvidenceQuorumChecks());
    // the master seal is the seal of the newest original proof here: its signature verdict is shared
    assertTrue(summary.getQuorumChecks() <= 1, "the master seal is checked at most once");

    // the input is untouched, and only the replaceable proofs differ in the output
    assertArrayEquals(before, token.toCbor());
    assertEquals(token.getGenesis().getTokenId(), consolidated.getGenesis().getTokenId());
    for (int i = 0; i < token.getTransactions().size(); i++) {
      assertArrayEquals(
          token.getTransactions().get(i).getInclusionProof().getCertificationData().toCbor(),
          consolidated.getTransactions().get(i).getInclusionProof().getCertificationData().toCbor());
      assertEquals(token.getTransactions().get(i).getInclusionProof().getReferenceTime(),
          consolidated.getTransactions().get(i).getInclusionProof().getReferenceTime());
    }
    // consolidating the result again is free of requests
    Batch again = new Batch();
    ConsolidationResult second = consolidate(consolidated, again, ConsolidationOptions.defaults());
    assertTrue(second.getSummary().isAlreadyConsolidated());
    assertSame(consolidated, second.getToken());
    assertEquals(0, again.sizes.size());
  }

  @Test
  void aTokenThatAlreadySharesOneSealMakesNoRequest() throws Exception {
    Token minted = TokenUtils.mintToken(client, context,
        SignaturePredicate.create(ALICE.getPublicKey()));
    Batch batch = new Batch();
    ConsolidationResult result = consolidate(minted, batch, ConsolidationOptions.defaults());
    assertEquals(ConsolidationResult.Status.CONSOLIDATED, result.getStatus());
    assertTrue(result.getSummary().isAlreadyConsolidated());
    assertSame(minted, result.getToken());
    assertEquals(0, batch.sizes.size());
    assertEquals(0, result.getSummary().getRequests());
  }

  @Test
  void aClientWithoutTheBatchCapabilityIsUnavailableWithoutAnyRequest() throws Exception {
    Token token = threeProofToken();
    AtomicInteger calls = new AtomicInteger();
    org.unicitylabs.sdk.api.AggregatorClient plain = new org.unicitylabs.sdk.api.AggregatorClient() {
      @Override
      public CompletableFuture<CertificationResponse> submitCertificationRequest(
          CertificationData data) {
        calls.incrementAndGet();
        throw new AssertionError();
      }

      @Override
      public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
        calls.incrementAndGet();
        throw new AssertionError();
      }

      @Override
      public CompletableFuture<Long> getLatestBlockNumber() {
        calls.incrementAndGet();
        throw new AssertionError();
      }
    };
    ConsolidationResult result = new TokenProofClient(ShardRouting.single(plain), context)
        .consolidate(token, ConsolidationOptions.defaults().withExecutor(Runnable::run)).get();
    assertEquals(ConsolidationResult.Status.UNAVAILABLE, result.getStatus());
    assertEquals(ProofUnavailableReason.UNSUPPORTED_CAPABILITY,
        result.getUnavailability().getReason());
    assertFalse(result.getUnavailability().isRetryable());
    assertEquals(0, calls.get());
    assertNull(result.getToken());
  }

  @Test
  void aTokenThatDoesNotVerifyIsAnIntegrityFailureAndNothingIsRequested() throws Exception {
    Token token = threeProofToken();
    // the same token verified under another authority
    VerificationContext other = new VerificationContext(
        TestAggregatorClient.create().getTrustBase(), PredicateVerifierService.create(),
        new MintJustificationVerifierService(), new TokenIssuanceVerifierService(false));
    Batch batch = new Batch();
    CompletionException error = assertThrows(CompletionException.class,
        () -> new TokenProofClient(ShardRouting.single(batch), other)
            .consolidate(token, ConsolidationOptions.defaults().withExecutor(Runnable::run))
            .join());
    ConsolidationException failure = (ConsolidationException) error.getCause();
    assertEquals(ConsolidationException.Kind.INTEGRITY, failure.getKind());
    assertEquals(ConsolidationOutcome.INVALID_INPUT, failure.getOutcome());
    assertEquals(0, batch.sizes.size());
  }

  @Test
  void aFrozenServiceEndsAtTheDeadlineAndTheCallIsCancelled() throws Exception {
    Token token = threeProofToken();
    Batch batch = new Batch();
    batch.hang = new CompletableFuture<>();
    CompletableFuture<ConsolidationResult> future = new TokenProofClient(
        ShardRouting.single(batch), context).consolidate(token, ConsolidationOptions.defaults()
        .withTimeout(Duration.ofMillis(200)));
    CompletionException error = assertThrows(CompletionException.class, future::join);
    ConsolidationException failure = (ConsolidationException) error.getCause();
    assertEquals(ConsolidationException.Kind.TIMEOUT, failure.getKind());
    assertTrue(failure.isRetryable());
    assertTrue(awaitCancelled(batch.hang), "the outstanding call was cancelled");
  }

  @Test
  void cancellingTheResultCancelsTheOutstandingCall() throws Exception {
    Token token = threeProofToken();
    Batch batch = new Batch();
    batch.hang = new CompletableFuture<>();
    CompletableFuture<ConsolidationResult> future = new TokenProofClient(
        ShardRouting.single(batch), context).consolidate(token, ConsolidationOptions.defaults());
    for (int i = 0; i < 100 && batch.sizes.isEmpty(); i++) {
      Thread.sleep(20);
    }
    assertEquals(1, batch.sizes.size());
    assertTrue(future.cancel(true));
    assertTrue(awaitCancelled(batch.hang));
    assertThrows(CancellationException.class, future::join);
  }

  @Test
  void anOperationOfTheCallerCancelsTheAttempt() throws Exception {
    Token token = threeProofToken();
    Batch batch = new Batch();
    batch.hang = new CompletableFuture<>();
    org.unicitylabs.sdk.internal.OperationContext operation =
        org.unicitylabs.sdk.internal.OperationContext.unbounded();
    CompletableFuture<ConsolidationResult> future = new TokenProofClient(
        ShardRouting.single(batch), context).consolidate(token,
        ConsolidationOptions.defaults().withOperation(operation));
    for (int i = 0; i < 100 && batch.sizes.isEmpty(); i++) {
      Thread.sleep(20);
    }
    operation.cancel();
    CompletionException error = assertThrows(CompletionException.class, future::join);
    assertEquals(ConsolidationException.Kind.CANCELLED,
        ((ConsolidationException) error.getCause()).getKind());
    assertTrue(awaitCancelled(batch.hang));
  }

  @Test
  void aCustomCertificateVerifierKeepsVerifyingEveryProofItself() throws Exception {
    Token token = threeProofToken();
    AtomicInteger verified = new AtomicInteger();
    UnicityCertificateVerifier counting = new UnicityCertificateVerifier() {
      @Override
      public org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerificationResult verify(
          org.unicitylabs.sdk.api.bft.RootTrustBase trustBase,
          org.unicitylabs.sdk.api.InclusionProof proof) {
        verified.incrementAndGet();
        return super.verify(trustBase, proof);
      }
    };
    VerificationContext custom = new VerificationContext(aggregator.getTrustBase(),
        PredicateVerifierService.create(), new MintJustificationVerifierService(),
        new TokenIssuanceVerifierService(false), counting);
    Batch batch = new Batch();
    ConsolidationResult result = new TokenProofClient(ShardRouting.single(batch), custom)
        .consolidate(token, ConsolidationOptions.defaults().withExecutor(Runnable::run)).get();
    assertEquals(ConsolidationResult.Status.CONSOLIDATED, result.getStatus());
    // three proofs before, three after: the custom verifier saw every one
    assertEquals(6, verified.get());
  }

  @Test
  void theOuterProofsAreReplacedAndEverythingElseIsCopiedByteForByte() throws Exception {
    Token token = threeProofToken();
    List<org.unicitylabs.sdk.api.InclusionProof> same = new ArrayList<>();
    same.add(token.getGenesis().getInclusionProof());
    for (CertifiedTransferTransaction transfer : token.getTransactions()) {
      same.add(transfer.getInclusionProof());
    }
    assertArrayEquals(token.toCbor(), TokenProofClient.replaceOuterProofs(token.toCbor(), same));
    assertThrows(ConsolidationException.class, () -> TokenProofClient.replaceOuterProofs(
        token.toCbor(), same.subList(0, 2)));
  }

  @Test
  void optionsAreValidatedWhenTheyAreBuilt() {
    assertThrows(IllegalArgumentException.class,
        () -> ConsolidationOptions.defaults().withMaxParallel(0));
    assertThrows(IllegalArgumentException.class,
        () -> ConsolidationOptions.defaults().withTimeout(Duration.ZERO));
    assertThrows(IllegalArgumentException.class,
        () -> ConsolidationOptions.defaults().withTimeout(Duration.ofDays(30)));
    IllegalArgumentException limit = assertThrows(IllegalArgumentException.class,
        () -> ConsolidationOptions.defaults().withTimeout(Duration.ofMinutes(10).plusMillis(1)));
    assertTrue(limit.getMessage().contains("10 minutes"), limit.getMessage());
    assertEquals(Duration.ofMinutes(10),
        ConsolidationOptions.defaults().withTimeout(Duration.ofMinutes(10)).getTimeout());
    assertThrows(IllegalArgumentException.class,
        () -> ConsolidationOptions.defaults().withMasterLongPoll(Duration.ofSeconds(-1),
            Duration.ofSeconds(2)));
    assertEquals(4, ConsolidationOptions.defaults().getMaxParallel());
    assertEquals(Duration.ofSeconds(10), ConsolidationOptions.defaults().getTimeout());
    assertFalse(ConsolidationOptions.defaults().isMasterLongPoll());
  }

  @Test
  void theMasterWaitIsOptInClippedToTheBudgetAndNeverOnAnExactRoot() throws Exception {
    Token token = threeProofToken();
    Batch batch = new Batch();
    consolidate(token, batch, ConsolidationOptions.defaults().withMasterLongPoll()
        .withTimeout(Duration.ofSeconds(10)));
    Long wait = batch.options.get(0).getWaitMillis();
    // min(20 s window, 10 s left - 2 s margin) = at most 8 s
    assertTrue(wait != null && wait > 0 && wait <= 8000, String.valueOf(wait));

    Batch tight = new Batch();
    consolidate(token, tight, ConsolidationOptions.defaults().withMasterLongPoll()
        .withTimeout(Duration.ofSeconds(1)));
    assertNull(tight.options.get(0).getWaitMillis(), "nothing fits the margin: no wait");
  }

  private static boolean awaitCancelled(CompletableFuture<?> future) throws InterruptedException {
    for (int i = 0; i < 100 && !future.isCancelled(); i++) {
      Thread.sleep(20);
    }
    return future.isCancelled();
  }

  @Test
  void theDeadlineIsNotQueuedBehindCpuWork() throws Exception {
    Token token = threeProofToken();
    Batch batch = new Batch();
    GateExecutor gate = new GateExecutor();
    // the answer is ready at once, but the work that verifies it waits in a saturated executor
    batch.onCall = () -> gate.hold = true;
    CompletableFuture<ConsolidationResult> future = new TokenProofClient(
        ShardRouting.single(batch), context).consolidate(token, ConsolidationOptions.defaults()
        .withTimeout(Duration.ofMillis(150)).withExecutor(gate));

    CompletionException error = assertThrows(CompletionException.class,
        () -> future.orTimeout(5, TimeUnit.SECONDS).join());
    ConsolidationException failure = (ConsolidationException) error.getCause();
    assertEquals(ConsolidationException.Kind.TIMEOUT, failure.getKind());
    assertTrue(gate.queued() > 0, "the verification work is still queued when the deadline hits");

    // releasing the work later restarts nothing and publishes nothing
    gate.release();
    assertEquals(1, batch.sizes.size());
    assertTrue(future.isCompletedExceptionally());
    assertEquals(ConsolidationException.Kind.TIMEOUT,
        ((ConsolidationException) assertThrows(CompletionException.class, future::join)
            .getCause()).getKind());
  }

  @Test
  void theDeadlineCancelsActiveCallsWhileTheCpuExecutorIsSaturated() throws Exception {
    Token token = threeProofToken();
    Batch batch = new Batch();
    batch.hang = new CompletableFuture<>();
    GateExecutor gate = new GateExecutor();
    batch.onCall = () -> gate.hold = true;
    CompletableFuture<ConsolidationResult> future = new TokenProofClient(
        ShardRouting.single(batch), context).consolidate(token, ConsolidationOptions.defaults()
        .withTimeout(Duration.ofMillis(150)).withExecutor(gate));

    CompletionException error = assertThrows(CompletionException.class,
        () -> future.orTimeout(5, TimeUnit.SECONDS).join());
    assertEquals(ConsolidationException.Kind.TIMEOUT,
        ((ConsolidationException) error.getCause()).getKind());
    assertTrue(awaitCancelled(batch.hang), "the outstanding call was cancelled by the timer");
    gate.release();
    assertEquals(1, batch.sizes.size());
  }

  @Test
  void aFinishedOperationReleasesItsTimers() throws Exception {
    Token token = threeProofToken();
    int baseline = org.unicitylabs.sdk.internal.Deadlines.pending();

    // success
    ConsolidationResult result = consolidate(token, new Batch(), ConsolidationOptions.defaults());
    assertEquals(ConsolidationResult.Status.CONSOLIDATED, result.getStatus());
    assertEquals(baseline, org.unicitylabs.sdk.internal.Deadlines.pending());

    // cancellation
    Batch hanging = new Batch();
    hanging.hang = new CompletableFuture<>();
    CompletableFuture<ConsolidationResult> cancelled = new TokenProofClient(
        ShardRouting.single(hanging), context).consolidate(token, ConsolidationOptions.defaults());
    for (int i = 0; i < 100 && hanging.sizes.isEmpty(); i++) {
      Thread.sleep(20);
    }
    assertTrue(org.unicitylabs.sdk.internal.Deadlines.pending() > baseline);
    cancelled.cancel(true);
    assertEquals(baseline, org.unicitylabs.sdk.internal.Deadlines.pending());

    // failure before any request, and a result that was complete before the timer was registered
    CompletableFuture<Object> done = new CompletableFuture<>();
    done.complete(null);
    org.unicitylabs.sdk.internal.Deadlines.scheduleFor(() -> { }, 60_000_000_000L, done);
    assertEquals(baseline, org.unicitylabs.sdk.internal.Deadlines.pending());
  }
}
