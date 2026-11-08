package org.unicitylabs.sdk.api.bft.trustbase;

/** The entry is well-formed but a structure rule fails (epoch, members, threshold, hashes, genesis shape). */
public class TrustBaseInvalidException extends TrustBaseException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public TrustBaseInvalidException(String message) {
    super(message);
  }
}
