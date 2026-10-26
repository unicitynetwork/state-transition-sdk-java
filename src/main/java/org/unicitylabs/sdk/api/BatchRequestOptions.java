package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.internal.OperationContext;

import java.time.Duration;
import java.util.Objects;

/**
 * Options of {@link BatchAggregatorClient#getInclusionProofs}.
 *
 * <p>Without a state root the request is for the latest finalized view. An exact root is never
 * replaced by the latest one, and an exact root request is never waited on: a state root and a wait
 * are mutually exclusive.
 */
public final class BatchRequestOptions {

  private static final BatchRequestOptions LATEST = new BatchRequestOptions(null, null, null, null);

  private final DataHash stateRoot;
  private final Long waitMillis;
  private final OperationContext operation;
  private final Duration callTimeout;

  private BatchRequestOptions(DataHash stateRoot, Long waitMillis, OperationContext operation,
      Duration callTimeout) {
    this.stateRoot = stateRoot;
    this.waitMillis = waitMillis;
    this.operation = operation;
    this.callTimeout = callTimeout;
  }

  /**
   * Options for the latest finalized view, without a wait.
   *
   * @return latest view options
   */
  public static BatchRequestOptions latest() {
    return LATEST;
  }

  /**
   * Options for the view of an exact state root.
   *
   * @param stateRoot the {@code InputRecord.hash} of the target certificate, a SHA-256 hash
   * @return exact root options
   * @throws IllegalArgumentException if the hash is not SHA-256
   */
  public static BatchRequestOptions exact(DataHash stateRoot) {
    if (stateRoot == null || stateRoot.getAlgorithm() != HashAlgorithm.SHA256) {
      throw new IllegalArgumentException("State root must be a SHA-256 hash.");
    }
    return new BatchRequestOptions(stateRoot, null, null, null);
  }

  /**
   * Asks the aggregator to hold a latest request for up to {@code waitMillis} milliseconds while
   * every state that is not yet proven is merely pending, and to echo the time it waited. {@code 0}
   * asks for the echo without waiting. A service without the feature rejects the unknown parameter;
   * the client then repeats the request once without it (see {@link InclusionProofBatch#getRequests()}).
   *
   * @param waitMillis value in 0..{@link WaitOptions#MAX_WAIT_MILLIS}
   * @return new options
   * @throws IllegalArgumentException if the value is out of range or an exact root is set
   */
  public BatchRequestOptions withWaitMillis(long waitMillis) {
    if (stateRoot != null) {
      throw new IllegalArgumentException("An exact state root request is never waited on.");
    }
    if (waitMillis < 0 || waitMillis > WaitOptions.MAX_WAIT_MILLIS) {
      throw new IllegalArgumentException("waitMillis must be in 0.." + WaitOptions.MAX_WAIT_MILLIS);
    }
    return new BatchRequestOptions(null, waitMillis, operation, callTimeout);
  }

  /**
   * Ties the call to the budget and cancellation of an operation.
   *
   * @param operation operation context
   * @return new options
   */
  public BatchRequestOptions withOperation(OperationContext operation) {
    return new BatchRequestOptions(stateRoot, waitMillis, operation, callTimeout);
  }

  /**
   * Bounds one HTTP call, in addition to the budget of the operation. A call that is held by the
   * aggregator needs a timeout of its wait plus a response margin, because the HTTP client's own
   * read timeout is shorter.
   *
   * @param callTimeout positive duration, at most {@link RequestOptions#MAX_CALL_TIMEOUT}
   * @return new options
   * @throws IllegalArgumentException if the duration is not positive or above the limit
   */
  public BatchRequestOptions withCallTimeout(Duration callTimeout) {
    Objects.requireNonNull(callTimeout, "callTimeout cannot be null");
    if (callTimeout.isNegative() || callTimeout.isZero()
        || callTimeout.compareTo(RequestOptions.MAX_CALL_TIMEOUT) > 0) {
      throw new IllegalArgumentException("callTimeout must be positive and at most "
          + RequestOptions.MAX_CALL_TIMEOUT.getSeconds() + " s (MAX_CALL_TIMEOUT).");
    }
    return new BatchRequestOptions(stateRoot, waitMillis, operation, callTimeout);
  }

  /**
   * Exact state root.
   *
   * @return root, null for the latest view
   */
  public DataHash getStateRoot() {
    return stateRoot;
  }

  /**
   * Requested wait.
   *
   * @return milliseconds, null when none is asked for
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
   * Timeout of one HTTP call.
   *
   * @return duration, null when the transport default applies
   */
  public Duration getCallTimeout() {
    return callTimeout;
  }
}
