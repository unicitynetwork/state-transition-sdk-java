package org.unicitylabs.sdk.api.bft.trustbase;

/** An entry does not extend its predecessor (network, epoch, start round, predecessor id). */
public class TrustBaseChainException extends TrustBaseException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public TrustBaseChainException(String message) {
    super(message);
  }
}
