package org.unicitylabs.sdk.api;

import java.time.Duration;
import java.util.Objects;
import org.unicitylabs.sdk.internal.OperationContext;

/**
 * Per-request options of aggregator client calls.
 *
 * <p>All parts are optional and the default adds nothing to the request: no wait parameter is
 * sent, no status hint is asked for, and the call is not tied to an operation budget.
 */
public final class RequestOptions {

  private static final RequestOptions NONE = new RequestOptions(null, null, false, null);

  private final Long waitMillis;
  private final OperationContext operation;
  private final boolean statusHint;
  private final Duration callTimeout;

  private RequestOptions(Long waitMillis, OperationContext operation, boolean statusHint,
      Duration callTimeout) {
    this.waitMillis = waitMillis;
    this.operation = operation;
    this.statusHint = statusHint;
    this.callTimeout = callTimeout;
  }

  /**
   * Options that change nothing.
   *
   * @return empty options
   */
  public static RequestOptions none() {
    return NONE;
  }

  /**
   * Asks the aggregator to hold the request up to {@code waitMillis} milliseconds while the state
   * is accepted but not yet certified. {@code 0} asks for the echo without waiting. The aggregator
   * clamps the value, and one without the feature ignores it, so neither the wait nor the echo is
   * promised.
   *
   * @param waitMillis value in 0..{@link WaitOptions#MAX_WAIT_MILLIS}
   * @return new options
   * @throws IllegalArgumentException if the value is out of range
   */
  public RequestOptions withWaitMillis(long waitMillis) {
    if (waitMillis < 0 || waitMillis > WaitOptions.MAX_WAIT_MILLIS) {
      throw new IllegalArgumentException("waitMillis must be in 0.." + WaitOptions.MAX_WAIT_MILLIS);
    }
    return new RequestOptions(waitMillis, operation, statusHint, callTimeout);
  }

  /**
   * Ties the call to the budget and cancellation of an operation.
   *
   * @param operation operation context
   * @return new options
   */
  public RequestOptions withOperation(OperationContext operation) {
    return new RequestOptions(waitMillis, operation, statusHint, callTimeout);
  }

  /**
   * Asks the aggregator for its unauthenticated status hint ({@link ProofStatusHint}) on an
   * inclusion proof lookup. Servers without the feature answer without it.
   *
   * @param statusHint true to ask for the hint
   * @return new options
   */
  public RequestOptions withStatusHint(boolean statusHint) {
    return new RequestOptions(waitMillis, operation, statusHint, callTimeout);
  }

  /**
   * Bounds one HTTP call, in addition to the budget of the operation. A call that is held by the
   * aggregator needs a timeout of its wait plus a response margin, because the HTTP client's own
   * read timeout is shorter.
   *
   * @param callTimeout positive duration
   * @return new options
   * @throws IllegalArgumentException if the duration is not positive
   */
  public RequestOptions withCallTimeout(Duration callTimeout) {
    Objects.requireNonNull(callTimeout, "callTimeout cannot be null");
    if (callTimeout.isNegative() || callTimeout.isZero()) {
      throw new IllegalArgumentException("callTimeout must be positive");
    }
    return new RequestOptions(waitMillis, operation, statusHint, callTimeout);
  }

  /**
   * Requested wait.
   *
   * @return wait in milliseconds, null when none is asked for
   */
  public Long getWaitMillis() {
    return waitMillis;
  }

  /**
   * Operation the call belongs to.
   *
   * @return operation context, null when the call is standalone
   */
  public OperationContext getOperation() {
    return operation;
  }

  /**
   * Tells if the status hint is asked for.
   *
   * @return true if the hint is requested
   */
  public boolean isStatusHint() {
    return statusHint;
  }

  /**
   * Timeout of one HTTP call.
   *
   * @return timeout, null for the transport's default
   */
  public Duration getCallTimeout() {
    return callTimeout;
  }
}
