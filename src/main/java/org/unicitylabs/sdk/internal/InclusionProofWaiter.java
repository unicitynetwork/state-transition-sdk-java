package org.unicitylabs.sdk.internal;

import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.InclusionProofWaitException;
import org.unicitylabs.sdk.api.ProofStatusHint;
import org.unicitylabs.sdk.api.RequestOptions;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.StatusAwareAggregatorClient;
import org.unicitylabs.sdk.api.WaitOptions;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcDataException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcNetworkException;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Asynchronous state machine that waits for the inclusion proof of a state.
 *
 * <p>One operation owns the whole wait: a monotonic budget that covers every call, pause and
 * verification, and the cancellation of the call in flight and of the pending pause. The first
 * lookup goes out at once, with no initial pause. While the aggregator holds a request (long poll)
 * the next one is sent as soon as the answer arrives; an answer without the echo, a quick answer or
 * a zero echo is paced at the polling interval, so the loop never spins. The status hint decides
 * only when to stop. A proof is verified off the transport thread before it is returned; nothing
 * but the verified proof ends the wait successfully.
 *
 * <p>Internal API, not part of the stable surface.
 */
public final class InclusionProofWaiter {

  /** Time source of pauses, replaceable by a fake in tests. */
  public interface Scheduler {

    /**
     * Runs the task after the delay.
     *
     * @param task task
     * @param delayMillis delay in milliseconds
     * @return handle that cancels a task that did not run yet
     */
    Runnable schedule(Runnable task, long delayMillis);
  }

  /** Verification of a received proof; throws the typed failure of the wait, returns on success. */
  @FunctionalInterface
  public interface ProofCheck {

    /**
     * Verifies the proof.
     *
     * @param proof received proof
     * @throws RuntimeException if the proof does not verify
     */
    void check(InclusionProof proof);
  }

  private static final class Shared {
    static final ScheduledExecutorService TIMER = timer();
    static final Executor VERIFIER = Executors.newFixedThreadPool(2, daemonFactory("verify"));

    private static ScheduledExecutorService timer() {
      ScheduledThreadPoolExecutor executor =
          new ScheduledThreadPoolExecutor(1, daemonFactory("wait-timer"));
      executor.setRemoveOnCancelPolicy(true);
      return executor;
    }
  }

  private static ThreadFactory daemonFactory(String name) {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "unicity-sdk-" + name + "-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private final AggregatorClient client;
  private final StateId stateId;
  private final ProofCheck check;
  private final WaitOptions options;
  private final Scheduler scheduler;
  private final Executor verifier;
  private final LongSupplier nanoClock;
  private final OperationContext operation;
  private final CompletableFuture<InclusionProof> result = new CompletableFuture<>();
  private final AtomicBoolean finished = new AtomicBoolean();
  private final long startNanos;
  private final long unknownGraceMillis;
  private volatile boolean longPolling;

  private InclusionProofWaiter(AggregatorClient client, StateId stateId, ProofCheck check,
      WaitOptions options, Scheduler scheduler, Executor verifier, LongSupplier nanoClock) {
    this.client = client;
    this.stateId = stateId;
    this.check = check;
    this.options = options;
    this.scheduler = scheduler;
    this.verifier = verifier;
    this.nanoClock = nanoClock;
    this.operation = OperationContext.withBudget(options.getTimeout(), nanoClock);
    this.startNanos = nanoClock.getAsLong();
    this.unknownGraceMillis = 2 * options.getInterval().toMillis();
    this.longPolling = options.isLongPoll() && client instanceof StatusAwareAggregatorClient;
  }

  /**
   * Waits for the proof with the shared timer and verifier threads.
   *
   * @param client aggregator client; one that is not a {@link StatusAwareAggregatorClient} is
   *     polled at the interval, without wait and status hints
   * @param stateId state to wait for
   * @param check verification of a received proof
   * @param options wait options
   * @return future of the verified proof; cancelling it cancels the call in flight
   */
  public static CompletableFuture<InclusionProof> await(AggregatorClient client, StateId stateId,
      ProofCheck check, WaitOptions options) {
    return await(client, stateId, check, options,
        (task, delay) -> {
          ScheduledFuture<?> handle = Shared.TIMER.schedule(task, delay, TimeUnit.MILLISECONDS);
          return () -> handle.cancel(false);
        }, Shared.VERIFIER, System::nanoTime);
  }

  /**
   * Waits for the proof with explicit scheduler, verifier executor and clock, so tests count
   * rather than sleep.
   *
   * @param client aggregator client
   * @param stateId state to wait for
   * @param check verification of a received proof
   * @param options wait options
   * @param scheduler source of pauses
   * @param verifier executor of the verification
   * @param nanoClock monotonic clock
   * @return future of the verified proof
   */
  public static CompletableFuture<InclusionProof> await(AggregatorClient client, StateId stateId,
      ProofCheck check, WaitOptions options, Scheduler scheduler, Executor verifier,
      LongSupplier nanoClock) {
    InclusionProofWaiter waiter = new InclusionProofWaiter(
        Objects.requireNonNull(client, "client cannot be null"),
        Objects.requireNonNull(stateId, "stateId cannot be null"),
        Objects.requireNonNull(check, "check cannot be null"),
        Objects.requireNonNull(options, "options cannot be null"),
        scheduler, verifier, nanoClock);
    waiter.result.whenComplete((proof, error) -> {
      if (waiter.result.isCancelled()) {
        waiter.finished.set(true);
        waiter.operation.cancel();
      }
    });
    waiter.poll();
    return waiter.result;
  }

  private void fail(Throwable error) {
    if (finished.compareAndSet(false, true)) {
      release();
      result.completeExceptionally(error);
    }
  }

  private void succeed(InclusionProof proof) {
    if (finished.compareAndSet(false, true)) {
      release();
      result.complete(proof);
    }
  }

  /** Runs the cancellation actions that are left (a pending pause, a call in flight). */
  private void release() {
    try {
      operation.cancel();
    } catch (RuntimeException e) {
      // nothing left to do for the caller; the actions all ran
    }
  }

  private long elapsedMillis() {
    return TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - startNanos);
  }

  private void poll() {
    if (finished.get()) {
      return;
    }
    if (operation.isCancelled()) {
      fail(new CancellationException("The wait was cancelled."));
      return;
    }
    if (operation.isExpired()) {
      fail(new TimeoutException("Timeout waiting for inclusion proof"));
      return;
    }

    long interval = options.getInterval().toMillis();
    boolean longPollConfigured = options.isLongPoll() && client instanceof StatusAwareAggregatorClient;
    long waitMillis = longPolling
        ? LongPoll.window(options.getWindow(), options.getMargin(), operation.remaining()) : 0;
    long callStarted = nanoClock.getAsLong();

    CompletableFuture<InclusionProofResponse> call;
    try {
      if (client instanceof StatusAwareAggregatorClient) {
        RequestOptions request = RequestOptions.none().withOperation(operation)
            .withStatusHint(true);
        if (waitMillis > 0) {
          Duration callTimeout = Duration.ofMillis(waitMillis).plus(options.getMargin());
          Duration left = operation.remaining();
          request = request.withWaitMillis(waitMillis)
              .withCallTimeout(left.compareTo(callTimeout) < 0 ? left : callTimeout);
        }
        call = ((StatusAwareAggregatorClient) client).getInclusionProof(stateId, request);
      } else {
        call = client.getInclusionProof(stateId);
      }
    } catch (RuntimeException e) {
      fail(e);
      return;
    }
    // the in-flight call is cancelled with the operation, also for a client that does not link its
    // own call to the operation
    CompletableFuture<InclusionProofResponse> inFlight = call;
    OperationContext.Registration registration = operation.onCancel(() -> inFlight.cancel(true));
    long asked = waitMillis;
    call.whenComplete((response, error) -> {
      registration.remove();
      long took = TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - callStarted);
      if (error == null) {
        onAnswer(response, asked, took, interval, longPollConfigured);
      } else {
        onFailure(unwrap(error), asked, interval, longPollConfigured);
      }
    });
  }

  private void onAnswer(InclusionProofResponse response, long asked, long tookMillis,
      long interval, boolean longPollConfigured) {
    if (finished.get()) {
      return;
    }
    if (response == null) {
      fail(new IllegalStateException("The aggregator client returned no response."));
      return;
    }
    InclusionProof proof = response.getInclusionProof();
    if (proof != null) {
      // verification is CPU work: never on the transport thread, never after a cancellation
      try {
        verifier.execute(() -> verify(proof));
      } catch (RuntimeException e) {
        fail(e);
      }
      return;
    }
    // Not certified yet. Unauthenticated hints decide only when to stop waiting.
    ProofStatusHint hint = response.getStatusHint();
    if (hint == ProofStatusHint.EXPIRED) {
      fail(new InclusionProofWaitException.Expired(stateId));
      return;
    }
    if (hint == ProofStatusHint.UNKNOWN && elapsedMillis() >= unknownGraceMillis) {
      fail(new InclusionProofWaitException.Unknown(stateId, elapsedMillis()));
      return;
    }
    Long pause = longPollConfigured ? Math.max(interval, LongPoll.MIN_POLL_GAP_MILLIS) : interval;
    if (asked > 0) {
      pause = LongPoll.pacedPause(interval, tookMillis, response.getWaitedMillis(),
          hint != ProofStatusHint.UNKNOWN);
      // an answer without the echo: the endpoint ignores the wait, poll it the normal way
      longPolling = response.getWaitedMillis() != null;
    }
    next(pause);
  }

  private void onFailure(Throwable cause, long asked, long interval, boolean longPollConfigured) {
    if (finished.get()) {
      return;
    }
    if (cause instanceof CancellationException || cause instanceof TimeoutException) {
      fail(cause);
      return;
    }
    // A request that outlived its own timeout, with the budget not spent, is an inconclusive
    // answer. The endpoint does not honor the wait then: poll the normal way.
    if (asked > 0 && !operation.isDone() && cause instanceof InterruptedIOException) {
      longPolling = false;
      next(Math.max(interval, LongPoll.MIN_POLL_GAP_MILLIS));
      return;
    }
    // A missing proof is the expected "not certified yet" answer: -32021 (certification pending)
    // always keeps polling. HTTP 404 and -32020 (no proof known for the state) are the legacy
    // "unknown" answer and follow the hint grace. Anything else ends the wait.
    int code = 0;
    boolean notFound = false;
    JsonRpcDataException rpc = null;
    if (cause instanceof JsonRpcDataException) {
      rpc = (JsonRpcDataException) cause;
    } else if (cause instanceof JsonRpcNetworkException) {
      JsonRpcNetworkException network = (JsonRpcNetworkException) cause;
      rpc = network.getRpcError();
      notFound = rpc == null && network.getStatus() == 404;
    }
    if (rpc != null) {
      code = rpc.getCode();
    }
    boolean pending = code == -32021;
    boolean unknown = notFound || code == -32020;
    if (!pending && !unknown) {
      fail(cause);
      return;
    }
    if (unknown && client instanceof StatusAwareAggregatorClient
        && elapsedMillis() >= unknownGraceMillis) {
      fail(new InclusionProofWaitException.Unknown(stateId, elapsedMillis()));
      return;
    }
    // these legacy errors carry no echo: the endpoint is not long polling
    if (asked > 0) {
      longPolling = false;
    }
    next(longPollConfigured ? Math.max(interval, LongPoll.MIN_POLL_GAP_MILLIS) : interval);
  }

  private void verify(InclusionProof proof) {
    if (finished.get() || operation.isCancelled()) {
      fail(new CancellationException("The wait was cancelled."));
      return;
    }
    try {
      check.check(proof);
    } catch (RuntimeException e) {
      fail(e);
      return;
    }
    if (operation.isCancelled()) {
      fail(new CancellationException("The wait was cancelled."));
      return;
    }
    succeed(proof);
  }

  /** Continues after a pause; null means at once. The pause never outlives the budget. */
  private void next(Long pauseMillis) {
    if (pauseMillis == null) {
      poll();
      return;
    }
    long left = operation.remaining().toMillis();
    long delay = Math.max(0, Math.min(pauseMillis, left));
    AtomicReference<OperationContext.Registration> registration = new AtomicReference<>();
    AtomicBoolean ran = new AtomicBoolean();
    Runnable cancel;
    try {
      cancel = scheduler.schedule(() -> {
        ran.set(true);
        OperationContext.Registration own = registration.get();
        if (own != null) {
          own.remove();
        }
        poll();
      }, delay);
    } catch (RuntimeException e) {
      fail(e);
      return;
    }
    OperationContext.Registration own = operation.onCancel(cancel);
    registration.set(own);
    if (ran.get()) {
      own.remove();
    }
  }

  private static Throwable unwrap(Throwable error) {
    return error instanceof CompletionException && error.getCause() != null
        ? error.getCause() : error;
  }
}
