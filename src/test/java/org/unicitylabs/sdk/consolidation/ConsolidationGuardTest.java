package org.unicitylabs.sdk.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.BatchAggregatorClient;
import org.unicitylabs.sdk.api.BatchRequestOptions;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofBatch;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.verification.OperationUnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerificationResult;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.CertifiedTransferTransaction;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifierService;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Regression tests of the guards that keep a faulty or hostile aggregator from substituting proofs
 * in a consolidation. Each test names the guard it pins; removing the guard makes it fail.
 */
class ConsolidationGuardTest {

  private static final SigningService ALICE = SigningService.generate();
  private static final SigningService BOB = SigningService.generate();
  private static final SigningService CAROL = SigningService.generate();
  private static final long START = 1_800_000_000L;

  private byte[] key;
  private VerificationContext context;
  private Token token;

  /** Serves batches out of one in-memory aggregator. */
  private static final class Batch implements BatchAggregatorClient {
    private final TestAggregatorClient source;

    Batch(TestAggregatorClient source) {
      this.source = source;
    }

    @Override
    public CompletableFuture<InclusionProofBatch> getInclusionProofs(List<StateId> stateIds,
        BatchRequestOptions options) {
      List<InclusionProofResponse> proofs = new ArrayList<>();
      for (StateId id : stateIds) {
        proofs.add(source.getInclusionProof(id).join());
      }
      return CompletableFuture.completedFuture(new InclusionProofBatch(proofs, null, 1, null));
    }

    @Override
    public CompletableFuture<CertificationResponse> submitCertificationRequest(
        CertificationData certificationData) {
      throw new AssertionError("a consolidation never submits");
    }

    @Override
    public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
      throw new AssertionError("a batch is never emulated by single requests");
    }

    @Override
    public CompletableFuture<Long> getLatestBlockNumber() {
      throw new AssertionError("not needed");
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    key = SigningService.generatePrivateKey();
    TestAggregatorClient original = TestAggregatorClient.create(key, START);
    StateTransitionClient client = new StateTransitionClient(original);
    context = new VerificationContext(original.getTrustBase(), PredicateVerifierService.create(),
        new MintJustificationVerifierService(), new TokenIssuanceVerifierService(false));
    Token minted = TokenUtils.mintToken(client, context,
        SignaturePredicate.create(ALICE.getPublicKey()));
    Token bob = TokenUtils.transferToken(client, context, minted.toCbor(),
        SignaturePredicate.create(BOB.getPublicKey()), ALICE);
    token = TokenUtils.transferToken(client, context, bob.toCbor(),
        SignaturePredicate.create(CAROL.getPublicKey()), BOB);
  }

  private List<ConsolidationItem> items() {
    List<ConsolidationItem> items = new ArrayList<>();
    items.add(new ConsolidationItem(token.getGenesis().getInclusionProof().getCertificationData(),
        token.getGenesis().getInclusionProof()));
    for (CertifiedTransferTransaction transfer : token.getTransactions()) {
      items.add(new ConsolidationItem(transfer.getInclusionProof().getCertificationData(),
          transfer.getInclusionProof()));
    }
    return items;
  }

  /** A second aggregator that certifies the very same requests, starting {@code shift} later. */
  private TestAggregatorClient reCertify(byte[] aggregatorKey, long shift) throws Exception {
    TestAggregatorClient other = TestAggregatorClient.create(aggregatorKey, START + shift);
    for (ConsolidationItem item : items()) {
      assertEquals(CertificationStatus.SUCCESS,
          other.submitCertificationRequest(item.getCertificationData()).get().getStatus());
    }
    return other;
  }

  private ProofConsolidator consolidator(BatchAggregatorClient batch) {
    OperationUnicityCertificateVerifier operation = (OperationUnicityCertificateVerifier)
        new UnicityCertificateVerifier().beginOperation(VerificationPolicy.LEGACY_QUORUM,
            PartitionViewPolicy.none());
    return new ProofConsolidator(ShardRouting.single(batch), context.getTrustBase(), operation);
  }

  private CompletableFuture<ConsolidationAttempt> attempt(BatchAggregatorClient batch) {
    return consolidator(batch).consolidate(items(),
        ConsolidationOptions.defaults().withExecutor(Runnable::run));
  }

  private static ConsolidationException failure(CompletableFuture<?> future) {
    CompletionException error = assertThrows(CompletionException.class,
        () -> future.orTimeout(30, TimeUnit.SECONDS).join());
    return (ConsolidationException) error.getCause();
  }

  private CompletableFuture<ConsolidationResult> tokenClient(BatchAggregatorClient batch,
      VerificationContext verification) {
    return new TokenProofClient(ShardRouting.single(batch), verification)
        .consolidate(token, ConsolidationOptions.defaults().withExecutor(Runnable::run));
  }

  @Test
  void controlAnHonestReCertificationAtTheOriginalTimesConsolidates() throws Exception {
    // the control of the tests below: the same re-certification, with nothing altered
    TestAggregatorClient honest = reCertify(key, 0);
    assertEquals(ConsolidationAttempt.Status.CONSOLIDATED,
        attempt(new Batch(honest)).get(30, TimeUnit.SECONDS).getStatus());
    assertEquals(ConsolidationResult.Status.CONSOLIDATED,
        tokenClient(new Batch(honest), context).get(30, TimeUnit.SECONDS).getStatus());
  }

  /** Guard: ProofConsolidator.replace compares the source proof's reference time with the original. */
  @Test
  void aSameKeyRewriterWithAShiftedReferenceTimeIsRefused() throws Exception {
    TestAggregatorClient rewriter = reCertify(key, 3);
    long shifted = rewriter.getInclusionProof(StateId.fromCertificationData(
        items().get(0).getCertificationData())).get().getInclusionProof().getReferenceTime();
    assertEquals(START + 3, shifted, "the rewriter really certifies at another time");

    ConsolidationException direct = failure(attempt(new Batch(rewriter)));
    assertEquals(ConsolidationException.Kind.INTEGRITY, direct.getKind());
    assertEquals(ConsolidationOutcome.BINDING_MISMATCH, direct.getOutcome());
    assertTrue(direct.getMessage().contains("reference time"), direct.getMessage());

    ConsolidationException viaClient = failure(tokenClient(new Batch(rewriter), context));
    assertEquals(ConsolidationException.Kind.INTEGRITY, viaClient.getKind());
    assertEquals(ConsolidationOutcome.BINDING_MISMATCH, viaClient.getOutcome());
    assertTrue(viaClient.getMessage().contains("reference time"), viaClient.getMessage());
  }

  /** Guard: ProofConsolidator.authenticateDirect authenticates the seal of an unsharded target. */
  @Test
  void aDirectTargetSealResignedByAnAlienKeyIsRefused() throws Exception {
    TestAggregatorClient alien = reCertify(SigningService.generatePrivateKey(), 0);

    ConsolidationException direct = failure(attempt(new Batch(alien)));
    assertEquals(ConsolidationException.Kind.INTEGRITY, direct.getKind());
    assertEquals(ConsolidationOutcome.UNAUTHENTICATED, direct.getOutcome());
    assertTrue(direct.getMessage().contains("Authenticating unicity seal"), direct.getMessage());

    ConsolidationException viaClient = failure(tokenClient(new Batch(alien), context));
    assertEquals(ConsolidationException.Kind.INTEGRITY, viaClient.getKind());
    assertEquals(ConsolidationOutcome.UNAUTHENTICATED, viaClient.getOutcome());
  }

  /**
   * Guard: TokenProofClient verifies the rebuilt token again, with the caller's verification. A
   * caller whose verifier refuses what the consolidation's own checks accepted still gets no
   * token.
   */
  @Test
  void theRebuiltTokenIsVerifiedAgainWithTheCallersVerifier() throws Exception {
    TestAggregatorClient honest = reCertify(key, 0);
    // the caller's own rule: it accepts the proofs of the token it holds (the first verification)
    // and refuses what comes after, as under another authority
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    int originalProofs = items().size();
    UnicityCertificateVerifier callersRule = new UnicityCertificateVerifier() {
      @Override
      public UnicityCertificateVerificationResult verify(RootTrustBase trustBase,
          InclusionProof proof) {
        if (calls.incrementAndGet() > originalProofs) {
          return super.verify(TestAggregatorClient.create().getTrustBase(), proof);
        }
        return super.verify(trustBase, proof);
      }
    };
    VerificationContext custom = new VerificationContext(context.getTrustBase(),
        PredicateVerifierService.create(), new MintJustificationVerifierService(),
        new TokenIssuanceVerifierService(false), callersRule);

    // the consolidation's own checks accept the honest batch...
    assertEquals(ConsolidationAttempt.Status.CONSOLIDATED,
        attempt(new Batch(honest)).get(30, TimeUnit.SECONDS).getStatus());
    // ...but the rebuilt token does not verify under the caller's verification: no token
    ConsolidationException refused = failure(tokenClient(new Batch(honest), custom));
    assertEquals(ConsolidationException.Kind.INTEGRITY, refused.getKind(), refused.toString());
    assertTrue(refused.getMessage().contains("consolidated token does not verify"),
        refused.getMessage());
  }

  /**
   * A seal session that is as busy as it allows is a retryable resource limit of the operation, not
   * an invalid response of the service.
   */
  @Test
  void aBusyVerificationSessionIsReportedAsABusyRetryableFailure() throws Exception {
    TestAggregatorClient honest = reCertify(key, 0);
    org.unicitylabs.sdk.api.bft.BlockingTrustBase blocking =
        org.unicitylabs.sdk.api.bft.BlockingTrustBase.copyOf(context.getTrustBase());
    OperationUnicityCertificateVerifier operation = (OperationUnicityCertificateVerifier)
        new UnicityCertificateVerifier().beginOperation(VerificationPolicy.LEGACY_QUORUM,
            PartitionViewPolicy.none());
    org.unicitylabs.sdk.api.bft.verification.SealVerificationSession session =
        operation.sessionFor(blocking);

    // every slot of the session is taken by a check that does not finish
    blocking.arm();
    int slots = org.unicitylabs.sdk.api.bft.verification.SealVerificationSession.DEFAULT_MAX_IN_FLIGHT;
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(slots);
    SigningService signer = new SigningService(key);
    try {
      for (int i = 0; i < slots; i++) {
        byte[] hash = new byte[32];
        hash[0] = (byte) i;
        hash[1] = 1;
        org.unicitylabs.sdk.api.bft.UnicitySeal seal =
            org.unicitylabs.sdk.api.bft.UnicityCertificateUtils.generateCertificate(signer,
                new org.unicitylabs.sdk.crypto.hash.DataHash(
                    org.unicitylabs.sdk.crypto.hash.HashAlgorithm.SHA256, hash))
                .getUnicitySeal();
        pool.submit(() -> session.verifyLegacyQuorum(seal));
      }
      for (int i = 0; i < 500 && blocking.entered() < slots; i++) {
        Thread.sleep(10);
      }
      assertEquals(slots, blocking.entered(), "the session is full");

      ConsolidationException direct = failure(new ProofConsolidator(
          ShardRouting.single(new Batch(honest)), blocking, operation).consolidate(items(),
          ConsolidationOptions.defaults().withExecutor(Runnable::run)));
      assertEquals(ConsolidationException.Kind.BUSY, direct.getKind());
      assertTrue(direct.isRetryable());
      assertTrue(direct.getCause() instanceof
          org.unicitylabs.sdk.api.bft.verification.SealVerificationBusyException);

      VerificationContext shared = new VerificationContext(blocking,
          PredicateVerifierService.create(), new MintJustificationVerifierService(),
          new TokenIssuanceVerifierService(false), operation);
      ConsolidationException viaClient = failure(tokenClient(new Batch(honest), shared));
      assertEquals(ConsolidationException.Kind.BUSY, viaClient.getKind());
      assertTrue(viaClient.isRetryable());
    } finally {
      blocking.release();
      pool.shutdown();
    }
  }

  // A caller-supplied executor that refuses work ends the attempt at once, typed.

  private static final class RefusingExecutor implements java.util.concurrent.Executor {
    private final java.util.concurrent.atomic.AtomicInteger accepted =
        new java.util.concurrent.atomic.AtomicInteger();
    private final int accept;

    RefusingExecutor(int accept) {
      this.accept = accept;
    }

    @Override
    public void execute(Runnable task) {
      if (accepted.incrementAndGet() > accept) {
        throw new java.util.concurrent.RejectedExecutionException("saturated");
      }
      task.run();
    }
  }

  @Test
  void anExecutorThatRefusesTheFirstTaskCompletesTheFutureTypedAndReleasesTheTimer()
      throws Exception {
    int baseline = org.unicitylabs.sdk.internal.Deadlines.pending();
    CompletableFuture<ConsolidationResult> future;
    try {
      future = new TokenProofClient(ShardRouting.single(new Batch(reCertify(key, 0))), context)
          .consolidate(token, ConsolidationOptions.defaults().withExecutor(new RefusingExecutor(0)));
    } catch (RuntimeException thrown) {
      throw new AssertionError("the call must not throw, it returns the future", thrown);
    }
    ConsolidationException failure = failure(future);
    assertEquals(ConsolidationException.Kind.INPUT, failure.getKind());
    assertTrue(failure.getCause() instanceof java.util.concurrent.RejectedExecutionException);
    assertEquals(baseline, org.unicitylabs.sdk.internal.Deadlines.pending(),
        "the deadline timer is released at once");
  }

  @Test
  void anExecutorThatRefusesALaterTaskIsReportedAtOnceNotAtTheDeadline() throws Exception {
    TestAggregatorClient honest = reCertify(key, 0);
    boolean refusedLate = false;
    for (int accept = 1; accept <= 8; accept++) {
      long started = System.nanoTime();
      CompletableFuture<ConsolidationResult> future = new TokenProofClient(
          ShardRouting.single(new Batch(honest)), context).consolidate(token,
          ConsolidationOptions.defaults().withTimeout(java.time.Duration.ofSeconds(20))
              .withExecutor(new RefusingExecutor(accept)));
      try {
        ConsolidationResult result = future.get(10, TimeUnit.SECONDS);
        assertEquals(ConsolidationResult.Status.CONSOLIDATED, result.getStatus());
      } catch (java.util.concurrent.ExecutionException e) {
        ConsolidationException failure = (ConsolidationException) e.getCause();
        assertEquals(ConsolidationException.Kind.INPUT, failure.getKind(), "accept=" + accept);
        refusedLate = true;
      }
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5),
          "reported at once, not at the 20 s deadline (accept=" + accept + ")");
    }
    assertTrue(refusedLate, "some refusal happened after the first task");
  }
}
