package org.unicitylabs.sdk.api.bft;

/**
 * A partition view is not valid, or could not be authenticated. Unchecked, typed, with the cause
 * preserved.
 */
public class PartitionViewException extends RuntimeException {

  /** Why the view was rejected. */
  public enum Reason {
    /** Not canonical CBOR, wrong tags/arrays/version, trailing data or null where not allowed. */
    ENCODING,
    /** A limit (bytes, leaves, shard bits, input record size) was exceeded. */
    BOUNDS,
    /** Shard set is not complete and prefix-free, or has a wrong order. */
    TOPOLOGY,
    /** The rebuilt tree does not match the enclosing certificate. */
    COMMITMENT,
    /** The enclosing seal was not authenticated. */
    AUTHENTICATION,
    /** The view does not satisfy the caller's pins or partition. */
    POLICY
  }

  private final Reason reason;

  /**
   * Creates the exception.
   *
   * @param reason why the view was rejected
   * @param message detail
   */
  public PartitionViewException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Creates the exception with a cause.
   *
   * @param reason why the view was rejected
   * @param message detail
   * @param cause the underlying failure
   */
  public PartitionViewException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  /**
   * Why the view was rejected.
   *
   * @return reason
   */
  public Reason getReason() {
    return reason;
  }
}
