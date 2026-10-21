package org.unicitylabs.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
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
    /** Runs what the timer fires: completion callbacks must never block the one timer thread. */
    static final Executor FIRED = Executors.newCachedThreadPool(daemonFactory("fired"));

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

  /** JSON-RPC code of the aggregator's concurrency limit ("busy"). */
  private static final int OVERLOAD_CODE = -32006;
  /** Longest Retry-After honored, in seconds. */
  private static final long MAX_RETRY_AFTER_SECONDS = 60;

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
    return await(client, stateId, check, options, InclusionProofWaiter::scheduleOnSharedTimer,
        Shared.VERIFIER, System::nanoTime);
  }

  /**
   * Runs a task on the shared timer thread after a delay (a deadline, a pause).
   *
   * @param task task, run on a pool thread after the delay
   * @param delayMillis delay in milliseconds
   * @return handle that cancels a task that did not run yet
   */
  public static Runnable scheduleOnSharedTimer(Runnable task, long delayMillis) {
    // the timer thread only hands the task over: the callbacks that completing a future runs may
    // block or take long, and a deadline of another operation must not wait for them
    ScheduledFuture<?> handle = Shared.TIMER.schedule(() -> Shared.FIRED.execute(task), delayMillis,
        TimeUnit.MILLISECONDS);
    return () -> handle.cancel(false);
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
    // one deadline for the whole operation: it ends a call that never completes, a verification
    // that runs late and a pause, and cancels what is active
    long budgetMillis = Math.max(0, options.getTimeout().toMillis());
    Runnable cancelDeadline = scheduler.schedule(() -> waiter.fail(
        new TimeoutException("Timeout waiting for inclusion proof")), budgetMillis);
    waiter.operation.onCancel(cancelDeadline);
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
    // the call timeout is the wait plus the margin and never above the limit of one call: the
    // wait gives way, the margin stays
    waitMillis = Math.min(waitMillis,
        Math.max(0, RequestOptions.MAX_CALL_TIMEOUT.minus(options.getMargin()).toMillis()));
    long callStarted = nanoClock.getAsLong();

    CompletableFuture<InclusionProofResponse> call;
    try {
      if (client instanceof StatusAwareAggregatorClient) {
        RequestOptions request = RequestOptions.none().withOperation(operation)
            .withStatusHint(true);
        if (waitMillis > 0) {
          Duration callTimeout = Duration.ofMillis(waitMillis).plus(options.getMargin());
          Duration left = operation.remaining();
          if (left.compareTo(callTimeout) < 0) {
            callTimeout = left;
          }
          if (callTimeout.compareTo(RequestOptions.MAX_CALL_TIMEOUT) > 0) {
            callTimeout = RequestOptions.MAX_CALL_TIMEOUT;
          }
          request = request.withWaitMillis(waitMillis).withCallTimeout(callTimeout);
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
    if (operation.isExpired()) {
      // an answer after the deadline is discarded
      fail(new TimeoutException("Timeout waiting for inclusion proof"));
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
    if (operation.isExpired()) {
      fail(new TimeoutException("Timeout waiting for inclusion proof"));
      return;
    }
    // A request that outlived its own timeout, with the budget not spent, is an inconclusive
    // answer. The endpoint does not honor the wait then: poll the normal way.
    if (asked > 0 && !operation.isDone() && cause instanceof InterruptedIOException) {
      longPolling = false;
      next(Math.max(interval, LongPoll.MIN_POLL_GAP_MILLIS));
      return;
    }
    // A transport failure of one read (connection refused or reset, a stale pooled connection, a
    // truncated response, a timeout of this call) says nothing about the state: poll on, plain
    // reads, within the budget. Certificate and hostname validation failures stay terminal.
    if (cause instanceof java.io.IOException && !isTlsValidationFailure(cause)) {
      onTransportFailure(cause, interval);
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
      if (!rpc.isIdMatched()) {
        // An error that carries no id of this request is no "pending" or "unknown" answer of ours.
        // The one exception is the server-wide busy answer, sent before the request is parsed, with
        // a null id: -32006 or data.retryable is an overload whoever it answers.
        if (isOverload(cause, rpc)) {
          onOverload(cause, interval);
        } else {
          fail(cause);
        }
        return;
      }
      code = rpc.getCode();
    }
    // An overload answer is decided before pending, unknown and the other statuses: a retryable
    // -32020 or -32021 is an overload, and a retryable unknown never ends the wait or resubmits.
    if (isOverload(cause, rpc)) {
      onOverload(cause, interval);
      return;
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

  /**
   * The inconclusive answer of a proof read: HTTP 503 or 429 (overload), 502 or 504 (an
   * intermediary's error), JSON-RPC -32006, or any JSON-RPC error whose data says
   * {@code retryable: true}.
   */
  private static boolean isOverload(Throwable cause, JsonRpcDataException rpc) {
    if (!(cause instanceof JsonRpcDataException)
        && cause instanceof JsonRpcNetworkException) {
      int status = ((JsonRpcNetworkException) cause).getStatus();
      // 503 and 429 are overload; 502 and 504 are an intermediary (gateway, load balancer) that
      // gave up on a request: the aggregator may well be fine. All four are inconclusive in a wait.
      if (status == 503 || status == 429 || status == 502 || status == 504) {
        return true;
      }
    }
    if (rpc == null) {
      return false;
    }
    if (rpc.getCode() == OVERLOAD_CODE) {
      return true;
    }
    JsonNode retryable = rpc.getData() == null ? null : rpc.getData().get("retryable");
    return retryable != null && retryable.isBoolean() && retryable.booleanValue();
  }

  private static boolean isTlsValidationFailure(Throwable cause) {
    for (Throwable t = cause; t != null; t = t.getCause()) {
      if (t instanceof javax.net.ssl.SSLPeerUnverifiedException
          || t instanceof java.security.cert.CertificateException) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  /**
   * A transport failure of a read is inconclusive: pause the interval and keep polling without the
   * wait. A pause that does not fit in the time left ends the wait now with a timeout that names
   * the failure.
   */
  private void onTransportFailure(Throwable cause, long interval) {
    long left = operation.remaining().toMillis();
    if (interval > left) {
      TimeoutException timeout = new TimeoutException("Transport failure (" + cause
          + "); the " + interval + " ms pause does not fit in the " + left
          + " ms left for the inclusion proof");
      timeout.initCause(cause);
      fail(timeout);
      return;
    }
    longPolling = false;
    next(interval);
  }

  /**
   * An overload answer is inconclusive: pause {@code max(interval, Retry-After)} and keep polling
   * without the wait for the rest of the call. A pause that does not fit in the time left ends the
   * wait now with a timeout that names the overload.
   */
  private void onOverload(Throwable cause, long interval) {
    long retryAfterMillis = 0;
    if (cause instanceof JsonRpcNetworkException) {
      Long seconds = ((JsonRpcNetworkException) cause).getRetryAfterSeconds();
      if (seconds != null) {
        retryAfterMillis = Math.min(seconds, MAX_RETRY_AFTER_SECONDS) * 1000;
      }
    }
    boolean hinted = retryAfterMillis > 0;
    long pause = Math.max(interval, retryAfterMillis);
    long left = operation.remaining().toMillis();
    if (pause > left) {
      String answer = cause instanceof JsonRpcDataException
          ? "JSON-RPC error " + ((JsonRpcDataException) cause).getCode()
          : "HTTP " + ((JsonRpcNetworkException) cause).getStatus();
      boolean intermediary = !(cause instanceof JsonRpcDataException)
          && (((JsonRpcNetworkException) cause).getStatus() == 502
          || ((JsonRpcNetworkException) cause).getStatus() == 504);
      TimeoutException timeout = new TimeoutException(
          (intermediary ? "Intermediary error (" : "Aggregator overloaded (") + answer + "), "
              + (hinted ? "retry-after " + (retryAfterMillis / 1000) + " s" : "no retry-after was given")
              + "; the " + pause + " ms pause does not fit in the " + left
              + " ms left for the inclusion proof");
      timeout.initCause(cause);
      fail(timeout);
      return;
    }
    longPolling = false;
    next(pause);
  }

  private void verify(InclusionProof proof) {
    if (finished.get() || operation.isCancelled()) {
      fail(new CancellationException("The wait was cancelled."));
      return;
    }
    if (operation.isExpired()) {
      fail(new TimeoutException("Timeout waiting for inclusion proof"));
      return;
    }
    try {
      check.check(proof);
    } catch (RuntimeException e) {
      fail(operation.isExpired() ? new TimeoutException("Timeout waiting for inclusion proof") : e);
      return;
    }
    if (operation.isCancelled()) {
      fail(new CancellationException("The wait was cancelled."));
      return;
    }
    if (operation.isExpired()) {
      // running crypto is not interrupted, but a late success is discarded
      fail(new TimeoutException("Timeout waiting for inclusion proof"));
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
