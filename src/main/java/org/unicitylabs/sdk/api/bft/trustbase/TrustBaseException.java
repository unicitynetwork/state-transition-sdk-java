package org.unicitylabs.sdk.api.bft.trustbase;

/** Base of the failures of the trust-base entry and history layer. Unchecked and typed by subclass. */
public class TrustBaseException extends RuntimeException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public TrustBaseException(String message) {
    super(message);
  }

  /**
   * Create the exception with its cause.
   *
   * @param message what failed
   * @param cause underlying failure
   */
  public TrustBaseException(String message, Throwable cause) {
    super(message, cause);
  }
}
