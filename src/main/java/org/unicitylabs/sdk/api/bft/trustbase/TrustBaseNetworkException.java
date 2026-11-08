package org.unicitylabs.sdk.api.bft.trustbase;

/** An entry or response belongs to another network than the history it is offered to. */
public class TrustBaseNetworkException extends TrustBaseChainException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public TrustBaseNetworkException(String message) {
    super(message);
  }
}
