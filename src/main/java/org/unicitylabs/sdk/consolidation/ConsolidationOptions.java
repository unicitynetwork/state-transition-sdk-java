package org.unicitylabs.sdk.consolidation;

import java.time.Duration;
import java.util.concurrent.Executor;
import org.unicitylabs.sdk.api.WaitOptions;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.internal.OperationContext;

/**
 * Options of one consolidation attempt. Immutable; every {@code with} method returns a new
 * instance.
 *
 * <p>The total deadline covers the whole attempt: requests, parsing and verification. Cancelling
 * the returned future, or the {@linkplain #withOperation operation}, cancels the attempt and every
 * outstanding request.
 */
public final class ConsolidationOptions {

  /** Default most concurrent exact-root requests of the second wave. */
  public static final int DEFAULT_MAX_PARALLEL = 4;
  /** Default total deadline of an attempt. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
  /** Longest total deadline an attempt accepts: 10 minutes. */
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

  private static final ConsolidationOptions DEFAULTS =
      new ConsolidationOptions(null, false, WaitOptions.DEFAULT_WINDOW, WaitOptions.DEFAULT_MARGIN,
          DEFAULT_MAX_PARALLEL, DEFAULT_TIMEOUT, null, null);

  private final ShardId master;
  private final boolean masterLongPoll;
  private final Duration window;
  private final Duration margin;
  private final int maxParallel;
  private final Duration timeout;
  private final OperationContext operation;
  private final Executor executor;

  private ConsolidationOptions(ShardId master, boolean masterLongPoll, Duration window,
      Duration margin, int maxParallel, Duration timeout, OperationContext operation,
      Executor executor) {
    this.master = master;
    this.masterLongPoll = masterLongPoll;
    this.window = window;
    this.margin = margin;
    this.maxParallel = maxParallel;
    this.timeout = timeout;
    this.operation = operation;
    this.executor = executor;
  }

  /**
   * The defaults: master chosen from the evidence, no wait, 4 parallel requests, 10 s.
   *
   * @return default options
   */
  public static ConsolidationOptions defaults() {
    return DEFAULTS;
  }

  /**
   * A master shard to use instead of the shard of the newest original proof; it must take part in
   * the token's outer history. Diagnostics and special cases: ordinary callers never need it.
   *
   * @param master master shard
   * @return new options
   */
  public ConsolidationOptions withMaster(ShardId master) {
    return new ConsolidationOptions(master, masterLongPoll, window, margin, maxParallel, timeout,
        operation, executor);
  }

  /**
   * Opts in to one long poll on the master shard's <em>latest</em> request, with the preferred
   * window of 20 s and margin of 2 s. The wait is clamped to what the total deadline leaves minus
   * the margin, and omitted when nothing fits. It applies to that one request only: exact-root
   * requests to the other shards never wait, and nothing is polled again. The one exception is the
   * aggregator's generic {@code malformed_params} answer to a wait it does not know: the request is
   * repeated once without it and both requests are counted.
   *
   * @return new options
   */
  public ConsolidationOptions withMasterLongPoll() {
    return new ConsolidationOptions(master, true, window, margin, maxParallel, timeout, operation,
        executor);
  }

  /**
   * Opts in to the master long poll with a tuned window and margin.
   *
   * @param window preferred window, not negative; zero turns the wait off
   * @param margin response margin, not negative
   * @return new options
   * @throws IllegalArgumentException if a duration is null, negative or longer than the wire
   *     maximum of a wait
   */
  public ConsolidationOptions withMasterLongPoll(Duration window, Duration margin) {
    if (window == null || window.isNegative() || margin == null || margin.isNegative()
        || window.toMillis() > WaitOptions.MAX_WAIT_MILLIS) {
      throw new IllegalArgumentException(
          "Long poll window and margin must be set and not negative, window within the wire maximum.");
    }
    return new ConsolidationOptions(master, !window.isZero(), window, margin, maxParallel, timeout,
        operation, executor);
  }

  /**
   * Upper bound on concurrent exact-root requests of the second wave.
   *
   * @param maxParallel at least 1
   * @return new options
   * @throws IllegalArgumentException if smaller than 1
   */
  public ConsolidationOptions withMaxParallel(int maxParallel) {
    if (maxParallel < 1) {
      throw new IllegalArgumentException("maxParallel must be an integer of at least 1.");
    }
    return new ConsolidationOptions(master, masterLongPoll, window, margin, maxParallel, timeout,
        operation, executor);
  }

  /**
   * Total deadline of the whole attempt.
   *
   * @param timeout positive, at most {@link #MAX_TIMEOUT}
   * @return new options
   * @throws IllegalArgumentException if out of range
   */
  public ConsolidationOptions withTimeout(Duration timeout) {
    if (timeout == null || timeout.isNegative() || timeout.isZero()
        || timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "timeout must be positive and at most 10 minutes (ConsolidationOptions.MAX_TIMEOUT).");
    }
    return new ConsolidationOptions(master, masterLongPoll, window, margin, maxParallel, timeout,
        operation, executor);
  }

  /**
   * Ties the attempt to an operation of the caller: cancelling it cancels the attempt, and its
   * remaining budget bounds the attempt's deadline.
   *
   * @param operation operation context
   * @return new options
   */
  public ConsolidationOptions withOperation(OperationContext operation) {
    return new ConsolidationOptions(master, masterLongPoll, window, margin, maxParallel, timeout,
        operation, executor);
  }

  /**
   * Executor of the CPU work that follows a response (decoding, verification). By default the
   * common pool; the work never runs on a transport callback thread.
   *
   * @param executor executor
   * @return new options
   */
  public ConsolidationOptions withExecutor(Executor executor) {
    return new ConsolidationOptions(master, masterLongPoll, window, margin, maxParallel, timeout,
        operation, executor);
  }

  /**
   * Master shard.
   *
   * @return master, null to choose from the evidence
   */
  public ShardId getMaster() {
    return master;
  }

  /**
   * Whether the master's latest request asks for a long poll.
   *
   * @return true if opted in
   */
  public boolean isMasterLongPoll() {
    return masterLongPoll;
  }

  /**
   * Preferred long poll window.
   *
   * @return window
   */
  public Duration getWindow() {
    return window;
  }

  /**
   * Long poll response margin.
   *
   * @return margin
   */
  public Duration getMargin() {
    return margin;
  }

  /**
   * Most concurrent exact-root requests.
   *
   * @return limit
   */
  public int getMaxParallel() {
    return maxParallel;
  }

  /**
   * Total deadline.
   *
   * @return timeout
   */
  public Duration getTimeout() {
    return timeout;
  }

  /**
   * Operation of the caller.
   *
   * @return operation, null if none
   */
  public OperationContext getOperation() {
    return operation;
  }

  /**
   * Executor of the CPU work after a response.
   *
   * @return executor, null for the default
   */
  public Executor getExecutor() {
    return executor;
  }
}
