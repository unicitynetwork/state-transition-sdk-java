package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.internal.OperationContext;

/**
 * Per-request options of aggregator client calls.
 *
 * <p>Both parts are optional and the default adds nothing to the request: no wait parameter is
 * sent, and the call is not tied to an operation budget.
 */
public final class RequestOptions {

  private static final RequestOptions NONE = new RequestOptions(null, null);

  private final Long waitMillis;
  private final OperationContext operation;

  private RequestOptions(Long waitMillis, OperationContext operation) {
    this.waitMillis = waitMillis;
    this.operation = operation;
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
    return new RequestOptions(waitMillis, operation);
  }

  /**
   * Ties the call to the budget and cancellation of an operation.
   *
   * @param operation operation context
   * @return new options
   */
  public RequestOptions withOperation(OperationContext operation) {
    return new RequestOptions(waitMillis, operation);
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
}
