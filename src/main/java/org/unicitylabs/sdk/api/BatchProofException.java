package org.unicitylabs.sdk.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Failure of a batch inclusion proof call ({@code get_inclusion_proofs.v1}), typed so that
 * applications switch on {@link #getKind()} and {@link #getDetail()} and never parse JSON-RPC reason
 * strings. A transport adapter completes the future of
 * {@link BatchAggregatorClient#getInclusionProofs} exceptionally with this class; caller
 * cancellation is never wrapped.
 *
 * <p>State status details ({@code state_pending}, {@code state_expired}, {@code state_unknown},
 * {@code state_not_in_root}) are unauthenticated hints about what one aggregator knows. They
 * classify a failure; they never make a token invalid or a state unspent.
 */
public class BatchProofException extends RuntimeException {

  /** Classification of a failed call. */
  public enum Kind {
    /**
     * The service cannot serve the material now (state not in the snapshot, view not retained, no
     * finalized or partition view) or is under pressure. Inputs are intact, a new attempt may
     * succeed.
     */
    UNAVAILABLE,
    /** The endpoint does not support the method or the configuration. */
    UNSUPPORTED,
    /** The service refused the request itself; retrying the same input cannot succeed. */
    REJECTED,
    /** A method limit (state count, request or response size) was hit. */
    LIMIT_EXCEEDED,
    /** Operational failure of the service (database, internal or unknown error code). */
    SERVER,
    /** The HTTP exchange failed. */
    TRANSPORT,
    /** The response is malformed or inconsistent with the request. */
    INVALID_RESPONSE
  }

  /** Details of the state status errors of {@code get_inclusion_proofs.v1}. */
  public static final List<String> STATE_DETAILS = Collections.unmodifiableList(
      List.of("state_expired", "state_not_in_root", "state_pending", "state_unknown"));

  private final Kind kind;
  private final Integer code;
  private final String detail;
  private final List<Integer> indexes;
  private final boolean retryable;
  private final int requests;
  private final Long waitedMillis;

  /**
   * Creates the exception.
   *
   * @param kind classification
   * @param message message
   * @param code JSON-RPC error code, null if the failure was not a JSON-RPC error
   * @param detail service detail such as {@code view_not_retained}, {@code busy:pinned_views} or
   *     {@code state_count}, null if none
   * @param indexes positions in the request's state id list that the service names as offending
   *     (state status details only), null if none
   * @param retryable whether a new attempt may succeed
   * @param requests HTTP requests the failed call sent: 0 for a rejection before anything was
   *     sent, 1, or 2 after the retry without a wait
   * @param waitedMillis the aggregator's unauthenticated echo of the time it waited, null if none
   * @param cause underlying failure, may be null
   */
  public BatchProofException(Kind kind, String message, Integer code, String detail,
      List<Integer> indexes, boolean retryable, int requests, Long waitedMillis,
      Throwable cause) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "kind cannot be null");
    this.code = code;
    this.detail = detail;
    this.indexes = indexes == null ? null
        : Collections.unmodifiableList(new ArrayList<>(indexes));
    this.retryable = retryable;
    this.requests = requests;
    this.waitedMillis = waitedMillis;
  }

  /**
   * Creates the exception with the common fields only.
   *
   * @param kind classification
   * @param message message
   * @param detail service detail, null if none
   * @param retryable whether a new attempt may succeed
   */
  public BatchProofException(Kind kind, String message, String detail, boolean retryable) {
    this(kind, message, null, detail, null, retryable, 0, null, null);
  }

  /**
   * Classification.
   *
   * @return kind
   */
  public Kind getKind() {
    return kind;
  }

  /**
   * JSON-RPC error code.
   *
   * @return code, null if none
   */
  public Integer getCode() {
    return code;
  }

  /**
   * Service detail.
   *
   * @return detail, null if none
   */
  public String getDetail() {
    return detail;
  }

  /**
   * Offending positions of a state status failure.
   *
   * @return unmodifiable list, null if the service sent none
   */
  public List<Integer> getIndexes() {
    return indexes;
  }

  /**
   * Whether a new attempt may succeed; never a promise that the same root will be served again.
   *
   * @return retryable
   */
  public boolean isRetryable() {
    return retryable;
  }

  /**
   * HTTP requests the failed call sent.
   *
   * @return 0, 1 or 2
   */
  public int getRequests() {
    return requests;
  }

  /**
   * The aggregator's unauthenticated echo of how long it held the request. Timing metadata only,
   * it never extends a deadline.
   *
   * @return milliseconds, null if none
   */
  public Long getWaitedMillis() {
    return waitedMillis;
  }

  /**
   * Tells whether a detail is one of the state status details.
   *
   * @param detail detail of a batch error
   * @return true for a state status detail
   */
  public static boolean isStateDetail(String detail) {
    return detail != null && STATE_DETAILS.contains(detail);
  }
}
