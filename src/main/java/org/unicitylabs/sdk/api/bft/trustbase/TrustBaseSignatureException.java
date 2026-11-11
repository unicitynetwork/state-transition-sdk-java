package org.unicitylabs.sdk.api.bft.trustbase;

/** A signature is not valid, a signer is not a member, or the quorum is not reached. */
public class TrustBaseSignatureException extends TrustBaseException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public TrustBaseSignatureException(String message) {
    super(message);
  }
}
