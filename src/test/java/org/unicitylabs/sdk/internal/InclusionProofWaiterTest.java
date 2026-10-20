package org.unicitylabs.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.InclusionProofWaitException;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.ProofStatusHint;
import org.unicitylabs.sdk.api.RequestOptions;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.StatusAwareAggregatorClient;
import org.unicitylabs.sdk.api.WaitOptions;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcDataException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcNetworkException;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.transaction.MintTransaction;

/**
 * The wait as a state machine, driven by a scripted client, a fake scheduler and a fake clock: the
 * assertions count requests, waits and pauses, nothing sleeps.
 */
class InclusionProofWaiterTest {

  /** One scripted answer of the fake aggregator. */
  private static final class Reply {
    final InclusionProofResponse response;
    final Throwable error;
    final long takes;

    Reply(InclusionProofResponse response, Throwable error, long takes) {
      this.response = response;
      this.error = error;
      this.takes = takes;
    }
  }

  /** The request a call carried. */
  private static final class Seen {
    final Long waitMillis;
    final boolean statusHint;
    final Duration callTimeout;
    final OperationContext operation;
    final boolean plain;

    Seen(Long waitMillis, boolean statusHint, Duration callTimeout, OperationContext operation,
        boolean plain) {
      this.waitMillis = waitMillis;
      this.statusHint = statusHint;
      this.callTimeout = callTimeout;
      this.operation = operation;
      this.plain = plain;
    }
  }

  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private final List<Seen> seen = new ArrayList<>();
  private final Deque<Reply> script = new ArrayDeque<>();
  private final List<Long> pauses = new ArrayList<>();
  private final List<Runnable> scheduled = new ArrayList<>();
  private final List<AtomicBoolean> scheduledCancelled = new ArrayList<>();
  private final List<CompletableFuture<InclusionProofResponse>> held = new ArrayList<>();
  /** The deadline task of each wait: the first thing a wait schedules. */
  private final List<Runnable> deadlines = new ArrayList<>();
  private final List<AtomicBoolean> deadlinesCancelled = new ArrayList<>();
  private boolean nextIsDeadline;

  private TestAggregatorClient aggregator;
  private StateId stateId;
  private InclusionProofResponse certified;
  private InclusionProofResponse pending;

  @BeforeEach
  void setUp() throws Exception {
    aggregator = TestAggregatorClient.create();
    MintTransaction transaction = MintTransaction.builder(NetworkId.LOCAL,
        SignaturePredicate.fromSigningService(SigningService.generate()))
        .expiresAt(System.currentTimeMillis() / 1000 + 600).build();
    stateId = StateId.fromTransaction(transaction);
    pending = aggregator.getInclusionProof(stateId).get();
    aggregator.submitCertificationRequest(CertificationData.fromMintTransaction(transaction)).get();
    certified = aggregator.getInclusionProof(stateId).get();
  }

  private InclusionProofWaiter.Scheduler scheduler() {
    return (task, delay) -> {
      if (nextIsDeadline) {
        nextIsDeadline = false;
        AtomicBoolean cancelledDeadline = new AtomicBoolean();
        deadlines.add(task);
        deadlinesCancelled.add(cancelledDeadline);
        return () -> cancelledDeadline.set(true);
      }
      pauses.add(delay);
      scheduled.add(task);
      AtomicBoolean cancelled = new AtomicBoolean();
      scheduledCancelled.add(cancelled);
      return () -> cancelled.set(true);
    };
  }

  /** Runs the one pending pause: the clock moves by the pause and the task runs. */
  private void firePause() {
    Runnable task = scheduled.remove(0);
    AtomicBoolean cancelled = scheduledCancelled.remove(0);
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(pauses.get(pauses.size() - 1)));
    if (!cancelled.get()) {
      task.run();
    }
  }

  private void answer(InclusionProofResponse response, long takesMillis) {
    script.add(new Reply(response, null, takesMillis));
  }

  private void fail(Throwable error) {
    script.add(new Reply(null, error, 0));
  }

  private StatusAwareAggregatorClient client() {
    return new StatusAwareAggregatorClient() {
      @Override
      public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId id,
          RequestOptions options) {
        seen.add(new Seen(options.getWaitMillis(), options.isStatusHint(), options.getCallTimeout(),
            options.getOperation(), false));
        return next();
      }

      @Override
      public CompletableFuture<CertificationResponse> submitCertificationRequest(
          CertificationData data, RequestOptions options) {
        throw new UnsupportedOperationException();
      }

      @Override
      public CompletableFuture<CertificationResponse> submitCertificationRequest(
          CertificationData data) {
        throw new UnsupportedOperationException();
      }

      @Override
      public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId id) {
        throw new AssertionError("a status aware client is asked with options");
      }

      @Override
      public CompletableFuture<Long> getLatestBlockNumber() {
        return CompletableFuture.completedFuture(1L);
      }
    };
  }

  private CompletableFuture<InclusionProofResponse> next() {
    Reply reply = script.poll();
    if (reply == null) {
      CompletableFuture<InclusionProofResponse> never = new CompletableFuture<>();
      held.add(never);
      return never;
    }
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(reply.takes));
    return reply.error != null ? CompletableFuture.failedFuture(reply.error)
        : CompletableFuture.completedFuture(reply.response);
  }

  private CompletableFuture<InclusionProof> await(AggregatorClient client, WaitOptions options,
      InclusionProofWaiter.ProofCheck check) {
    nextIsDeadline = true;
    return InclusionProofWaiter.await(client, stateId, check, options, scheduler(), Runnable::run,
        nanos::get);
  }

  private CompletableFuture<InclusionProof> await(WaitOptions options) {
    return await(client(), options, proof -> { });
  }

  private static Throwable failure(CompletableFuture<?> future) {
    try {
      future.get(1, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      return e.getCause();
    } catch (CancellationException e) {
      return e;
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    throw new AssertionError("the future did not fail");
  }

  @Test
  void aDeadlineThatPassesWhileVerificationIsQueuedDiscardsTheLateWork() {
    answer(certified, 0);
    nextIsDeadline = true;
    List<Runnable> cpu = new ArrayList<>();
    AtomicLong checks = new AtomicLong();
    CompletableFuture<InclusionProof> result = InclusionProofWaiter.await(client(), stateId,
        proof -> checks.incrementAndGet(), WaitOptions.defaults(), scheduler(), cpu::add, nanos::get);
    assertEquals(1, cpu.size());
    assertFalse(result.isDone());
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(15));
    deadlines.get(0).run();
    assertInstanceOf(TimeoutException.class, failure(result));
    assertTrue(deadlinesCancelled.get(0).get());
    cpu.get(0).run();
    assertEquals(0, checks.get(), "late CPU work cannot verify or publish");
    assertEquals(1, seen.size(), "late CPU work cannot restart requests");
  }

  @Test
  void theRealTimerEndsTheWaitWhileVerificationIsStillRunning() throws Exception {
    answer(certified, 0);
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch exited = new java.util.concurrent.CountDownLatch(1);
    CompletableFuture<InclusionProof> result = InclusionProofWaiter.await(client(), stateId,
        proof -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exited.countDown();
          }
        }, WaitOptions.defaults().timeout(Duration.ofMillis(200)));
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertInstanceOf(TimeoutException.class, failure(result));
    } finally {
      release.countDown();
      assertTrue(exited.await(2, TimeUnit.SECONDS));
      result.cancel(true);
    }
    assertInstanceOf(TimeoutException.class, failure(result));
  }

  @Test
  void aProofThatArrivesAfterTheBudgetIsDiscarded() {
    answer(certified, 16_000);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertInstanceOf(TimeoutException.class, failure(result));
  }

  @Test
  void aVerificationThatUsesUpTheBudgetNeverSucceeds() {
    answer(certified, 0);
    CompletableFuture<InclusionProof> result = await(client(), WaitOptions.defaults(),
        proof -> nanos.addAndGet(TimeUnit.SECONDS.toNanos(16)));
    assertInstanceOf(TimeoutException.class, failure(result));
  }

  @Test
  void aCallThatNeverAnswersIsEndedByTheDeadlineAndCancelled() {
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertEquals(1, held.size());
    assertEquals(1, deadlines.size(), "the wait schedules one deadline for the whole operation");
    assertFalse(result.isDone());
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(15));
    deadlines.get(0).run();
    assertInstanceOf(TimeoutException.class, failure(result));
    assertTrue(held.get(0).isCancelled(), "the call in flight is cancelled with the deadline");
    assertEquals(1, seen.size());
  }

  @Test
  void theDeadlineTimerIsReleasedWhenTheWaitEnds() {
    answer(certified, 0);
    assertTrue(await(WaitOptions.defaults()).isDone());
    assertTrue(deadlinesCancelled.get(0).get(), "no timer outlives the wait");
  }

  @Test
  void anErrorThatCarriesNoIdOfOursIsNeitherPendingNorUnknown() {
    JsonRpcDataException unmatched = new JsonRpcDataException(-32021, "pending", null, false);
    fail(unmatched);
    assertSame(unmatched, failure(await(WaitOptions.defaults())));
    seen.clear();
    JsonRpcDataException unknown = new JsonRpcDataException(-32020, "unknown", null, false);
    fail(unknown);
    assertSame(unknown, failure(await(WaitOptions.defaults())));
  }

  @Test
  void theFirstProbeGoesOutAtOnceWithTheLongPollAndNothingIsScheduledBeforeIt() throws Exception {
    answer(certified.withWaitedMillis(120L), 120);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertTrue(result.isDone());
    assertSame(certified.getInclusionProof(), result.get());
    assertEquals(1, seen.size());
    assertTrue(pauses.isEmpty(), "no initial pause");
    // window 20 s, 15 s budget, 2 s margin: the budget caps the window; the call timeout is wait + margin
    assertEquals(13_000L, seen.get(0).waitMillis);
    assertTrue(seen.get(0).statusHint);
    assertEquals(Duration.ofSeconds(15), seen.get(0).callTimeout);
  }

  @Test
  void aHeldAnswerIsFollowedByTheNextRequestAtOnce() {
    // pending after a full hold: the aggregator echoed the wait, the call took at least the interval
    answer(pending.withStatusHint(ProofStatusHint.PENDING).withWaitedMillis(13_000L), 13_000);
    answer(certified.withWaitedMillis(300L), 300);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertTrue(result.isDone());
    assertEquals(2, seen.size(), "one request per hold");
    assertTrue(pauses.isEmpty(), "a real hold needs no pause");
    // 2 s are left after the first hold and the margin takes them: an ordinary lookup
    assertNull(seen.get(1).waitMillis);
  }

  @Test
  void aQuickOrZeroEchoIsPacedAtTheIntervalNeverSpun() {
    answer(pending.withStatusHint(ProofStatusHint.PENDING).withWaitedMillis(0L), 0);
    answer(pending.withStatusHint(ProofStatusHint.PENDING).withWaitedMillis(12_000L), 1);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertFalse(result.isDone());
    // zero echo: the rest of the interval
    assertEquals(List.of(1000L), pauses);
    firePause();
    // a "hold" that came back after 1 ms is not a hold either: the rest of the interval
    assertEquals(2, seen.size());
    assertEquals(List.of(1000L, 999L), pauses);
    assertEquals(12_000L, seen.get(1).waitMillis);
  }

  @Test
  void aMissingEchoMeansTheEndpointIgnoresTheWaitAndTheRestOfTheCallPollsAtTheInterval() {
    answer(pending, 0);
    answer(pending, 0);
    answer(certified, 0);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults().interval(Duration.ofMillis(250)));
    assertEquals(13_000L, seen.get(0).waitMillis);
    assertEquals(List.of(250L), pauses);
    firePause();
    assertNull(seen.get(1).waitMillis, "no wait is asked for once the echo was missing");
    assertEquals(List.of(250L, 250L), pauses);
    firePause();
    assertTrue(result.isDone());
    assertEquals(3, seen.size());
  }

  @Test
  void theWindowIsClippedByTheRemainingBudgetAndNoWaitFitsNearTheEnd() {
    WaitOptions options = WaitOptions.defaults().timeout(Duration.ofSeconds(5));
    answer(pending.withStatusHint(ProofStatusHint.PENDING).withWaitedMillis(3_000L), 3_000);
    answer(pending.withStatusHint(ProofStatusHint.PENDING).withWaitedMillis(0L), 0);
    CompletableFuture<InclusionProof> result = await(options);
    // 5 s budget, 2 s margin
    assertEquals(3_000L, seen.get(0).waitMillis);
    assertEquals(Duration.ofSeconds(5), seen.get(0).callTimeout);
    // 2 s left, 2 s margin: nothing fits, the second one is an ordinary lookup
    assertNull(seen.get(1).waitMillis);
    assertFalse(result.isDone());
    assertEquals(List.of(1000L), pauses);
  }

  @Test
  void theBudgetEndsTheWaitWithATimeoutAndNothingStartsAfterIt() {
    WaitOptions options = WaitOptions.defaults().timeout(Duration.ofSeconds(2)).longPoll(false);
    answer(pending, 0);
    answer(pending, 0);
    CompletableFuture<InclusionProof> result = await(options);
    assertEquals(List.of(1000L), pauses);
    firePause();
    assertEquals(2, seen.size());
    assertEquals(List.of(1000L, 1000L), pauses.subList(0, 2));
    firePause();
    assertInstanceOf(TimeoutException.class, failure(result));
    assertEquals(2, seen.size(), "no request after the deadline");
    assertTrue(scheduled.isEmpty());
  }

  @Test
  void anExpiredHintEndsTheWaitAtOnceAndAnUnknownOneAfterTheGrace() {
    answer(pending.withStatusHint(ProofStatusHint.EXPIRED), 0);
    InclusionProofWaitException.Expired expired = assertInstanceOf(InclusionProofWaitException.Expired.class,
        failure(await(WaitOptions.defaults())));
    assertTrue(expired.isResubmittable());
    assertEquals(1, seen.size());

    seen.clear();
    pauses.clear();
    scheduled.clear();
    scheduledCancelled.clear();
    answer(pending.withStatusHint(ProofStatusHint.UNKNOWN).withWaitedMillis(0L), 0);
    answer(pending.withStatusHint(ProofStatusHint.UNKNOWN).withWaitedMillis(0L), 0);
    answer(pending.withStatusHint(ProofStatusHint.UNKNOWN).withWaitedMillis(0L), 0);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    // the grace is two intervals from the start of the wait: first answer at 0 s, then 1 s, then 2 s
    assertFalse(result.isDone());
    firePause();
    assertFalse(result.isDone());
    firePause();
    InclusionProofWaitException.Unknown unknown = assertInstanceOf(
        InclusionProofWaitException.Unknown.class, failure(result));
    assertTrue(unknown.isResubmittable());
    assertEquals(3, seen.size());
  }

  @Test
  void theHintNeverOverridesACertifiedProof() {
    answer(certified.withStatusHint(ProofStatusHint.EXPIRED), 0);
    assertTrue(await(WaitOptions.defaults()).isDone());
    assertFalse(await(WaitOptions.defaults()).isCompletedExceptionally());
  }

  @Test
  void aProofIsVerifiedBeforeItIsReturnedAndOffTheCallingThread() throws Exception {
    List<Runnable> tasks = new ArrayList<>();
    answer(certified, 0);
    AtomicBoolean checked = new AtomicBoolean();
    CompletableFuture<InclusionProof> result = InclusionProofWaiter.await(client(), stateId,
        proof -> checked.set(true), WaitOptions.defaults(), scheduler(), tasks::add, nanos::get);
    assertFalse(result.isDone(), "not returned before it verified");
    assertEquals(1, tasks.size());
    tasks.get(0).run();
    assertTrue(checked.get());
    assertSame(certified.getInclusionProof(), result.get());
  }

  @Test
  void aProofThatDoesNotVerifyFailsTheWaitWithItsOwnErrorAndNothingIsRetried() {
    answer(certified, 0);
    IllegalStateException rejected = new IllegalStateException("rejected");
    CompletableFuture<InclusionProof> result = await(client(), WaitOptions.defaults(), proof -> {
      throw rejected;
    });
    assertSame(rejected, failure(result));
    assertEquals(1, seen.size());
    assertTrue(pauses.isEmpty());
  }

  @Test
  void cancellingTheFutureCancelsTheCallInFlight() {
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertEquals(1, held.size());
    assertFalse(held.get(0).isCancelled());
    result.cancel(true);
    assertTrue(held.get(0).isCancelled(), "the call in flight is cancelled");
    assertTrue(seen.get(0).operation.isCancelled());
    assertEquals(1, seen.size(), "nothing restarts");
  }

  @Test
  void cancellingDuringAPauseCancelsThePauseAndRestartsNothing() {
    answer(pending, 0);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertEquals(1, scheduled.size());
    result.cancel(true);
    assertTrue(scheduledCancelled.get(0).get(), "the pending pause is cancelled");
    // a late firing of the cancelled task starts nothing
    scheduled.get(0).run();
    assertEquals(1, seen.size());
  }

  @Test
  void aLocalCallTimeoutWithBudgetLeftIsInconclusiveAndFallsBackToPolling() {
    fail(new InterruptedIOException("timeout"));
    answer(certified, 0);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertEquals(List.of(1000L), pauses);
    firePause();
    assertNull(seen.get(1).waitMillis);
    assertTrue(result.isDone());
  }

  @Test
  void transportAndServiceErrorsEndTheWait() {
    JsonRpcDataException internal = new JsonRpcDataException(-32603, "db down", null, true);
    fail(internal);
    assertSame(internal, failure(await(WaitOptions.defaults())));
  }

  @Test
  void legacyPendingAndUnknownAnswersKeepPollingAndFollowTheGrace() {
    fail(new JsonRpcDataException(-32021, "pending", null, true));
    fail(new JsonRpcNetworkException(404, "not found"));
    fail(new JsonRpcDataException(-32020, "unknown", null, true));
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertFalse(result.isDone());
    firePause();
    assertFalse(result.isDone());
    firePause();
    assertInstanceOf(InclusionProofWaitException.Unknown.class, failure(result));
    assertEquals(3, seen.size());
    // these answers carry no echo: the endpoint does not long poll
    assertNull(seen.get(1).waitMillis);
  }

  @Test
  void aClientWithoutTheCapabilityIsPolledAtTheIntervalWithoutWaitOrHints() {
    List<StateId> asked = new ArrayList<>();
    AggregatorClient plain = new AggregatorClient() {
      @Override
      public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData d) {
        throw new UnsupportedOperationException();
      }

      @Override
      public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId id) {
        asked.add(id);
        return CompletableFuture.completedFuture(asked.size() < 3 ? pending : certified);
      }

      @Override
      public CompletableFuture<Long> getLatestBlockNumber() {
        return CompletableFuture.completedFuture(1L);
      }
    };
    CompletableFuture<InclusionProof> result = await(plain, WaitOptions.defaults(), proof -> { });
    assertEquals(List.of(1000L), pauses);
    firePause();
    firePause();
    assertTrue(result.isDone());
    assertEquals(3, asked.size());
  }

  @Test
  void concurrentWaitsDoNotShareTheirBudgetOrTheirCancellation() {
    answer(pending, 0);
    CompletableFuture<InclusionProof> first = await(WaitOptions.defaults().longPoll(false));
    CompletableFuture<InclusionProof> second = await(WaitOptions.defaults().longPoll(false));
    assertEquals(2, seen.size());
    first.cancel(true);
    assertFalse(seen.get(1).operation.isCancelled());
    assertTrue(seen.get(0).operation.isCancelled());
    assertFalse(second.isDone());
  }

  // Overload answers of a proof read (spec-wait-overload): inconclusive, paced, never a failure.

  @Test
  void anOverloadedReadIsInconclusiveThenTheCallPollsWithoutTheWait() {
    fail(new JsonRpcNetworkException(503, "Service Overloaded"));
    answer(pending.withStatusHint(ProofStatusHint.PENDING), 0);
    answer(certified, 0);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertFalse(result.isDone());
    assertEquals(13_000L, seen.get(0).waitMillis, "the first request long polls");
    assertEquals(List.of(1000L), pauses, "the pause is the interval when there is no Retry-After");
    firePause();
    assertNull(seen.get(1).waitMillis, "the rest of the call polls without the wait");
    assertEquals(List.of(1000L, 1000L), pauses);
    firePause();
    assertTrue(result.isDone());
    assertEquals(3, seen.size(), "no extra requests");
  }

  @Test
  void aBusyAnswerAndAnyRetryableErrorAreOverloadToo() {
    fail(new JsonRpcDataException(-32006, "Concurrency limit exceeded", null, true));
    com.fasterxml.jackson.databind.node.ObjectNode data =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    data.put("retryable", true);
    fail(new JsonRpcDataException(-32099, "try again", data, true));
    answer(certified, 0);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    firePause();
    firePause();
    assertTrue(result.isDone());
    assertEquals(3, seen.size());
    assertNull(seen.get(1).waitMillis);
    assertNull(seen.get(2).waitMillis);
    assertEquals(List.of(1000L, 1000L), pauses);
  }

  @Test
  void tooManyRequestsWithRetryAfterPausesLongerThanTheInterval() {
    fail(new JsonRpcNetworkException(429, "slow down", null, 4L));
    answer(certified, 0);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertEquals(List.of(4000L), pauses, "max(interval, Retry-After)");
    firePause();
    assertTrue(result.isDone());
    assertEquals(2, seen.size());
  }

  @Test
  void aRetryAfterAboveTheCapIsCappedAtSixtySecondsAndMustFit() {
    fail(new JsonRpcNetworkException(503, "overloaded", null, 3_600L));
    JsonRpcNetworkException overload = (JsonRpcNetworkException) script.peek().error;
    Throwable failure = failure(await(WaitOptions.defaults().timeout(Duration.ofSeconds(30))));
    assertInstanceOf(TimeoutException.class, failure);
    assertSame(overload, failure.getCause());
    assertTrue(failure.getMessage().contains("overloaded"));
    assertTrue(failure.getMessage().contains("retry-after 60 s"), failure.getMessage());
    assertEquals(1, seen.size(), "it fails at once, with the budget unused");
    assertTrue(pauses.isEmpty());
  }

  @Test
  void aRetryAfterBeyondTheRemainingBudgetFailsFastWithTheOverloadAsCause() {
    // 5 s asked, 3 s of the 15 s budget left
    answer(pending.withStatusHint(ProofStatusHint.PENDING), 12_000);
    fail(new JsonRpcNetworkException(503, "overloaded", null, 5L));
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults().longPoll(false));
    firePause();
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(11));
    Throwable failure = failure(result);
    assertInstanceOf(TimeoutException.class, failure);
    assertInstanceOf(JsonRpcNetworkException.class, failure.getCause());
    assertTrue(failure.getMessage().contains("retry-after 5 s"), failure.getMessage());
  }

  @Test
  void aRetryAfterThatIsNotNumericIsIgnoredByTheTransportAndTheIntervalApplies() {
    fail(new JsonRpcNetworkException(503, "overloaded", null, null));
    answer(certified, 0);
    CompletableFuture<InclusionProof> result = await(WaitOptions.defaults());
    assertEquals(List.of(1000L), pauses);
    firePause();
    assertTrue(result.isDone());
  }

  @Test
  void aNonRetryableErrorStillEndsTheWaitAtOnce() {
    JsonRpcNetworkException bad = new JsonRpcNetworkException(500, "boom");
    fail(bad);
    assertSame(bad, failure(await(WaitOptions.defaults())));
    seen.clear();
    com.fasterxml.jackson.databind.node.ObjectNode data =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    data.put("retryable", false);
    JsonRpcDataException refused = new JsonRpcDataException(-32099, "no", data, true);
    fail(refused);
    assertSame(refused, failure(await(WaitOptions.defaults())));
    assertEquals(1, seen.size());
    assertTrue(pauses.isEmpty());
  }

  @Test
  void anOverloadErrorThatCarriesNoIdOfOursIsNotAnOverloadAnswer() {
    JsonRpcDataException unmatched = new JsonRpcDataException(-32006, "busy", null, false);
    fail(unmatched);
    assertSame(unmatched, failure(await(WaitOptions.defaults())));
  }
}
