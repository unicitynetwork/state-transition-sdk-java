package org.unicitylabs.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.BatchProofException;
import org.unicitylabs.sdk.api.BatchRequestOptions;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationRejectedException;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.InclusionProofWaitException;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.NonInclusionProofException;
import org.unicitylabs.sdk.api.ProofStatusHint;
import org.unicitylabs.sdk.api.RequestOptions;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.StatusAwareAggregatorClient;
import org.unicitylabs.sdk.api.SubmitOutcome;
import org.unicitylabs.sdk.api.WaitOptions;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcDataException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcNetworkException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcResponseException;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.TokenSalt;
import org.unicitylabs.sdk.util.InclusionProofUtils;
import org.unicitylabs.sdk.util.verification.VerificationException;

/** The facade: capability fallbacks, idempotent resubmission and the bounded submit-and-wait. */
class StateTransitionClientTest {

  /** A status aware aggregator over the in-memory one, with a drop switch and request counters. */
  private static final class Scripted implements StatusAwareAggregatorClient {
    final TestAggregatorClient inner = TestAggregatorClient.create();
    final AtomicInteger submissions = new AtomicInteger();
    final AtomicInteger lookups = new AtomicInteger();
    final List<Long> waits = new ArrayList<>();
    final List<Boolean> hints = new ArrayList<>();
    int swallow;
    ProofStatusHint whenMissing = ProofStatusHint.UNKNOWN;
    private final List<StateId> registered = new ArrayList<>();

    @Override
    public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData data) {
      submissions.incrementAndGet();
      if (swallow > 0) {
        swallow--;
        return CompletableFuture.completedFuture(CertificationResponse.create(CertificationStatus.SUCCESS));
      }
      registered.add(StateId.fromCertificationData(data));
      return inner.submitCertificationRequest(data);
    }

    @Override
    public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData data,
        RequestOptions options) {
      return submitCertificationRequest(data);
    }

    @Override
    public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId id) {
      return inner.getInclusionProof(id);
    }

    @Override
    public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId id, RequestOptions options) {
      lookups.incrementAndGet();
      waits.add(options.getWaitMillis());
      hints.add(options.isStatusHint());
      return inner.getInclusionProof(id).thenApply(response -> {
        InclusionProofResponse answer = response.getInclusionProof() == null && !registered.contains(id)
            ? response.withStatusHint(whenMissing) : response;
        return options.getWaitMillis() == null ? answer : answer.withWaitedMillis(0L);
      });
    }

    @Override
    public CompletableFuture<Long> getLatestBlockNumber() {
      return inner.getLatestBlockNumber();
    }
  }

  private Scripted aggregator;
  private StateTransitionClient client;
  private MintTransaction transaction;
  private CertificationData data;

  @BeforeEach
  void setUp() {
    aggregator = new Scripted();
    client = new StateTransitionClient(aggregator);
    transaction = mint(TokenSalt.generate(), null);
    data = CertificationData.fromMintTransaction(transaction);
  }

  private static MintTransaction mint(TokenSalt salt, byte[] payload) {
    MintTransaction.Builder builder = MintTransaction.builder(NetworkId.LOCAL,
        SignaturePredicate.fromSigningService(SigningService.generate()))
        .salt(salt).expiresAt(System.currentTimeMillis() / 1000 + 600);
    return (payload == null ? builder : builder.data(payload)).build();
  }

  private static Throwable failure(CompletableFuture<?> future) {
    try {
      future.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      return e.getCause();
    } catch (CancellationException e) {
      return e;
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    throw new AssertionError("the future did not fail");
  }

  private WaitOptions fast() {
    return WaitOptions.defaults().interval(Duration.ofMillis(10)).timeout(Duration.ofSeconds(10));
  }

  // ---- the default wait ----------------------------------------------------------------------

  @Test
  void aSubmissionThatNeverCompletesIsEndedByTheDeadlineAndCancelled() {
    CompletableFuture<CertificationResponse> submission = new CompletableFuture<>();
    java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
    AggregatorClient raw = new AggregatorClient() {
      public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData d) {
        return submission;
      }
      public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId id) {
        lookups.incrementAndGet();
        return new CompletableFuture<>();
      }
      public CompletableFuture<Long> getLatestBlockNumber() { return CompletableFuture.completedFuture(1L); }
    };
    CompletableFuture<InclusionProof> result = new StateTransitionClient(raw).submitAndWait(
        transaction, data, 2, aggregator.inner.getTrustBase(), PredicateVerifierService.create(),
        WaitOptions.defaults().timeout(Duration.ofMillis(100)));
    assertInstanceOf(java.util.concurrent.TimeoutException.class, failure(result));
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(submission::isCancelled);
    assertEquals(0, lookups.get());
  }

  @Test
  void aNullSubmissionResultEndsTheResubmissionWithATypedFailure() {
    AggregatorClient raw = new AggregatorClient() {
      public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData d) {
        return CompletableFuture.completedFuture(null);
      }
      public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId id) { return new CompletableFuture<>(); }
      public CompletableFuture<Long> getLatestBlockNumber() { return CompletableFuture.completedFuture(1L); }
    };
    CompletableFuture<SubmitOutcome> result = new StateTransitionClient(raw)
        .resubmitCertificationRequest(data, RequestOptions.none());
    assertInstanceOf(JsonRpcResponseException.class, failure(result), "a null result must not strand the caller");
  }

  @Test
  void aDuplicateReportWithoutOurIdIsNotAnAcknowledgement() {
    AggregatorClient raw = new AggregatorClient() {
      public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData d) {
        return CompletableFuture.failedFuture(new JsonRpcDataException(-32603,
            "E11000 duplicate key index: stateId_1", null, false));
      }
      public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId id) { return new CompletableFuture<>(); }
      public CompletableFuture<Long> getLatestBlockNumber() { return CompletableFuture.completedFuture(1L); }
    };
    CompletableFuture<SubmitOutcome> result = new StateTransitionClient(raw)
        .resubmitCertificationRequest(data, RequestOptions.none());
    assertInstanceOf(JsonRpcDataException.class, failure(result));
  }

  @Test
  void cancellingBetweenTheSubmissionAndTheWaitCancelsTheNewWait() throws Exception {
    CompletableFuture<CertificationResponse> submit = new CompletableFuture<>();
    CompletableFuture<InclusionProofResponse> proofCall = new CompletableFuture<>();
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    AggregatorClient raw = new AggregatorClient() {
      public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData d) { return submit; }
      public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId id) {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return proofCall;
      }
      public CompletableFuture<Long> getLatestBlockNumber() { return CompletableFuture.completedFuture(1L); }
    };
    CompletableFuture<InclusionProof> result = new StateTransitionClient(raw).submitAndWait(
        transaction, data, 0, aggregator.inner.getTrustBase(), PredicateVerifierService.create(),
        WaitOptions.defaults());
    Thread complete = new Thread(() -> submit.complete(CertificationResponse.create(CertificationStatus.SUCCESS)));
    complete.start();
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      result.cancel(true);
    } finally {
      release.countDown();
      complete.join(3000);
    }
    assertTrue(proofCall.isCancelled(), "Cancellation raced with handoff and leaked the new call");
  }

  @Test
  void theDefaultWaitAsksForTheLongPollAndTheHintFromTheFirstProbe() throws Exception {
    aggregator.submitCertificationRequest(data).get();
    InclusionProof proof = InclusionProofUtils.waitInclusionProof(client, aggregator.inner.getTrustBase(),
        PredicateVerifierService.create(), transaction).get(10, TimeUnit.SECONDS);
    assertNotNull(proof.getCertificationData());
    assertEquals(1, aggregator.lookups.get(), "the proof was there: one request, no pause");
    // the 15 s default budget less the 2 s margin, less what was spent before the first request
    assertEquals(1, aggregator.waits.size());
    assertTrue(aggregator.waits.get(0) > 12_500L && aggregator.waits.get(0) <= 13_000L, "" + aggregator.waits);
    assertEquals(List.of(true), aggregator.hints);
  }

  @Test
  void theLegacyOptOutSendsNoWait() throws Exception {
    aggregator.submitCertificationRequest(data).get();
    InclusionProofUtils.waitInclusionProof(client, aggregator.inner.getTrustBase(),
        PredicateVerifierService.create(), transaction, WaitOptions.defaults().longPoll(false))
        .get(10, TimeUnit.SECONDS);
    assertEquals(java.util.Collections.singletonList(null), aggregator.waits);
  }

  @Test
  void theLegacyTimeoutIntervalOverloadStillLongPolls() throws Exception {
    aggregator.submitCertificationRequest(data).get();
    InclusionProofUtils.waitInclusionProof(client, aggregator.inner.getTrustBase(),
        PredicateVerifierService.create(), transaction, Duration.ofSeconds(5), Duration.ofMillis(10))
        .get(10, TimeUnit.SECONDS);
    // the budget leaves 5 s - 2 s margin, less what was spent before the first request
    assertEquals(1, aggregator.waits.size());
    assertTrue(aggregator.waits.get(0) > 2_500L && aggregator.waits.get(0) <= 3_000L, "" + aggregator.waits);
  }

  // ---- capabilities ----------------------------------------------------------------------------

  @Test
  void aClientWithoutTheCapabilitiesAnswersTypedAndNeverEmulates() {
    AggregatorClient plain = TestAggregatorClient.create();
    StateTransitionClient facade = new StateTransitionClient(plain);
    BatchProofException batch = assertInstanceOf(BatchProofException.class, failure(
        facade.getInclusionProofs(List.of(StateId.fromCertificationData(data)), BatchRequestOptions.latest())));
    assertEquals(BatchProofException.Kind.UNSUPPORTED, batch.getKind());
    assertEquals(0, batch.getRequests());
    assertEquals(NonInclusionProofException.Kind.REJECTED, assertInstanceOf(NonInclusionProofException.class,
        failure(facade.getNonInclusionProof(StateId.fromCertificationData(data), RequestOptions.none()))).getKind());
  }

  // ---- resubmission ----------------------------------------------------------------------------

  private StateTransitionClient replying(CompletableFuture<CertificationResponse> reply) {
    return new StateTransitionClient(new AggregatorClient() {
      @Override
      public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData d) {
        return reply;
      }

      @Override
      public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId id) {
        throw new UnsupportedOperationException();
      }

      @Override
      public CompletableFuture<Long> getLatestBlockNumber() {
        return CompletableFuture.completedFuture(1L);
      }
    });
  }

  private static CompletableFuture<CertificationResponse> status(CertificationStatus status) {
    return CompletableFuture.completedFuture(CertificationResponse.create(status));
  }

  private static JsonRpcDataException duplicate(String index) {
    return new JsonRpcDataException(-32603, "Failed to submit", com.fasterxml.jackson.databind.node.TextNode
        .valueOf("E11000 duplicate key error collection: aggregator.records index: " + index + " dup key"), true);
  }

  @Test
  void resubmissionReportsADuplicateByStateIdAsAlreadySubmittedAndOtherRejectionsAsErrors() throws Exception {
    assertEquals(SubmitOutcome.Kind.ACCEPTED, replying(status(CertificationStatus.SUCCESS))
        .resubmitCertificationRequest(data, RequestOptions.none()).get().getKind());
    assertEquals(SubmitOutcome.Kind.ALREADY_SUBMITTED, replying(status(CertificationStatus.STATE_ID_EXISTS))
        .resubmitCertificationRequest(data, RequestOptions.none()).get().getKind());
    assertEquals(SubmitOutcome.Kind.ALREADY_SUBMITTED, replying(status(CertificationStatus.ALREADY_SUBMITTED))
        .resubmitCertificationRequest(data, RequestOptions.none()).get().getKind());

    SubmitOutcome queued = replying(CompletableFuture.failedFuture(duplicate("stateId_1")))
        .resubmitCertificationRequest(data, RequestOptions.none()).get();
    assertEquals(SubmitOutcome.Kind.ALREADY_SUBMITTED, queued.getKind());
    assertEquals(CertificationStatus.STATE_ID_QUEUED, queued.getResponse().getStatus());
    // the aggregator's own error behind an http status is the same report
    assertEquals(SubmitOutcome.Kind.ALREADY_SUBMITTED, replying(CompletableFuture.failedFuture(
        new JsonRpcNetworkException(500, "x", duplicate("stateId_1"))))
        .resubmitCertificationRequest(data, RequestOptions.none()).get().getKind());

    // a duplicate on any other index is a real failure
    JsonRpcDataException other = duplicate("transactionHash_1");
    assertSame(other, failure(replying(CompletableFuture.failedFuture(other))
        .resubmitCertificationRequest(data, RequestOptions.none())));
    assertEquals(CertificationStatus.REQUEST_EXPIRED, assertInstanceOf(CertificationRejectedException.class,
        failure(replying(status(CertificationStatus.REQUEST_EXPIRED))
            .resubmitCertificationRequest(data, RequestOptions.none()))).getStatus());
  }

  // ---- submit and wait -------------------------------------------------------------------------

  @Test
  void submitAndWaitReturnsTheVerifiedProof() throws Exception {
    InclusionProof proof = client.submitAndWait(transaction, data, 2, aggregator.inner.getTrustBase(),
        PredicateVerifierService.create(), fast()).get(10, TimeUnit.SECONDS);
    assertNotNull(proof.getInclusionCertificate());
    assertEquals(1, aggregator.submissions.get());
  }

  @Test
  void aDroppedRequestIsResubmittedWithinTheBound() throws Exception {
    aggregator.swallow = 1;
    InclusionProof proof = client.submitAndWait(transaction, data, 1, aggregator.inner.getTrustBase(),
        PredicateVerifierService.create(), fast()).get(10, TimeUnit.SECONDS);
    assertNotNull(proof);
    assertEquals(2, aggregator.submissions.get());
  }

  @Test
  void theResubmissionBoundIsHonoured() {
    aggregator.swallow = 5;
    Throwable e = failure(client.submitAndWait(transaction, data, 0, aggregator.inner.getTrustBase(),
        PredicateVerifierService.create(), fast()));
    assertInstanceOf(InclusionProofWaitException.Unknown.class, e);
    assertEquals(1, aggregator.submissions.get());

    Scripted second = new Scripted();
    second.swallow = 5;
    second.whenMissing = ProofStatusHint.EXPIRED;
    Throwable expired = failure(new StateTransitionClient(second).submitAndWait(transaction, data, 2,
        second.inner.getTrustBase(), PredicateVerifierService.create(), fast()));
    assertInstanceOf(InclusionProofWaitException.Expired.class, expired);
    assertEquals(3, second.submissions.get(), "the first submission and two resubmissions");
  }

  @Test
  void aProofOfAnotherRequestIsAConflictAfterAuthenticationAndNeverResubmitted() throws Exception {
    TokenSalt salt = TokenSalt.generate();
    MintTransaction mine = mint(salt, new byte[] {1});
    MintTransaction theirs = mint(salt, new byte[] {2});
    assertEquals(StateId.fromTransaction(mine).toString(), StateId.fromTransaction(theirs).toString(),
        "two requests for one state id");
    aggregator.submitCertificationRequest(CertificationData.fromMintTransaction(theirs)).get();
    int before = aggregator.submissions.get();
    Throwable e = failure(client.submitAndWait(mine, CertificationData.fromMintTransaction(mine), 3,
        aggregator.inner.getTrustBase(), PredicateVerifierService.create(), fast()));
    assertInstanceOf(InclusionProofWaitException.Conflict.class, e);
    assertEquals(before + 1, aggregator.submissions.get(), "one submission, no resubmission for a conflict");
  }

  @Test
  void cancellingSubmitAndWaitCancelsTheWait() {
    aggregator.swallow = 100;
    CompletableFuture<InclusionProof> future = client.submitAndWait(transaction, data, 100,
        aggregator.inner.getTrustBase(), PredicateVerifierService.create(),
        WaitOptions.defaults().interval(Duration.ofMillis(200)).timeout(Duration.ofSeconds(30)));
    future.cancel(true);
    int lookups = aggregator.lookups.get();
    org.awaitility.Awaitility.await().pollDelay(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5))
        .until(() -> aggregator.lookups.get() <= lookups + 1);
    assertTrue(future.isCancelled());
  }

  @Test
  void aForgedProofOfAnotherRequestIsAVerificationFailureNotAConflictAndNothingIsResubmitted()
      throws Exception {
    TokenSalt salt = TokenSalt.generate();
    MintTransaction mine = mint(salt, new byte[] {1});
    MintTransaction theirs = mint(salt, new byte[] {2});
    assertEquals(StateId.fromTransaction(mine).toString(), StateId.fromTransaction(theirs).toString());
    // the proof of the other request is signed by a key the trust base does not know: it matches
    // neither this transaction nor the network, so it authenticates nothing
    aggregator.submitCertificationRequest(CertificationData.fromMintTransaction(theirs)).get();
    int before = aggregator.submissions.get();
    Throwable e = failure(client.submitAndWait(mine, CertificationData.fromMintTransaction(mine), 3,
        TestAggregatorClient.create().getTrustBase(), PredicateVerifierService.create(), fast()));
    assertInstanceOf(VerificationException.class, e);
    assertEquals(before + 1, aggregator.submissions.get(), "no resubmission for a proof that does not verify");
  }

  @Test
  void submitAndWaitRefusesCertificationDataThatIsNotThatOfTheTransaction() {
    MintTransaction other = mint(TokenSalt.generate(), null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> client.submitAndWait(
        transaction, CertificationData.fromMintTransaction(other), 1, aggregator.inner.getTrustBase(),
        PredicateVerifierService.create(), fast()));
    assertTrue(e.getMessage().contains("does not belong"));
    // the same state id with another transaction hash is refused too
    TokenSalt salt = TokenSalt.generate();
    MintTransaction mine = mint(salt, new byte[] {1});
    MintTransaction sameState = mint(salt, new byte[] {2});
    assertThrows(IllegalArgumentException.class, () -> client.submitAndWait(mine,
        CertificationData.fromMintTransaction(sameState), 1, aggregator.inner.getTrustBase(),
        PredicateVerifierService.create(), fast()));
    assertEquals(0, aggregator.submissions.get(), "nothing was submitted");
  }
}
