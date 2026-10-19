package org.unicitylabs.sdk.api;

/** Failure of a non-inclusion proof request. */
public class NonInclusionProofException extends RuntimeException {

  /** Class of the failure. */
  public enum Kind {
    /** The service is busy (proof admission limit). */
    BUSY,
    /** The caller cancelled the request. */
    CANCELLED,
    /** The service sent something that is not a valid answer. */
    INVALID_RESPONSE,
    /** The service rejected the request. */
    REJECTED,
    /** An internal or unknown service failure. */
    SERVER,
    /** The deadline of the request expired. */
    TIMEOUT,
    /** The request did not reach the service or the HTTP exchange failed. */
    TRANSPORT,
    /** The service has no certified snapshot yet. */
    UNAVAILABLE
  }

  private final Kind kind;
  private final Integer code;
  private final boolean retryable;

  /**
   * Creates the exception.
   *
   * @param kind class of the failure
   * @param message description
   * @param cause cause, may be null
   * @param code JSON-RPC code, null if none
   * @param retryable whether another attempt may help
   */
  public NonInclusionProofException(Kind kind, String message, Throwable cause, Integer code,
      boolean retryable) {
    super(message, cause);
    this.kind = kind;
    this.code = code;
    this.retryable = retryable;
  }

  /**
   * Class of the failure.
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
   * Tells if another attempt may help.
   *
   * @return true if retryable
   */
  public boolean isRetryable() {
    return retryable;
  }
}
