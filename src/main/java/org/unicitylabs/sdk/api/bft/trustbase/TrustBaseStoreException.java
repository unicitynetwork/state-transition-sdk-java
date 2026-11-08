package org.unicitylabs.sdk.api.bft.trustbase;

/** A typed storage failure. A failed read can never authorize a replacement of what it could not read. */
public class TrustBaseStoreException extends TrustBaseException {

  /** What failed. */
  public enum Reason {
    /** The stored value exceeds the read budget (checked before allocating). */
    OVERSIZE,
    /** Another process holds the store. */
    BUSY,
    /** No permission. */
    PERMISSION,
    /** Out of space or quota. */
    QUOTA,
    /** Any other I/O failure. */
    IO
  }

  private final Reason reason;

  /**
   * Create the exception.
   *
   * @param reason what failed
   * @param message details
   */
  public TrustBaseStoreException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Create the exception with its cause.
   *
   * @param reason what failed
   * @param message details
   * @param cause underlying failure
   */
  public TrustBaseStoreException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  /**
   * Get what failed.
   *
   * @return the reason
   */
  public Reason getReason() {
    return this.reason;
  }
}
