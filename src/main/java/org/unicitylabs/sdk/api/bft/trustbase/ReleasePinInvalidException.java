package org.unicitylabs.sdk.api.bft.trustbase;

/** The embedded pin is invalid. Fatal: never rescued by a cache or a remote source. */
public class ReleasePinInvalidException extends TrustBaseException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public ReleasePinInvalidException(String message) {
    super(message);
  }

  /**
   * Create the exception with its cause.
   *
   * @param message what failed
   * @param cause underlying failure
   */
  public ReleasePinInvalidException(String message, Throwable cause) {
    super(message, cause);
  }
}
