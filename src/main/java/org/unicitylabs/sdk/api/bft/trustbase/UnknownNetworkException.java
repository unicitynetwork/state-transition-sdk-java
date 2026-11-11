package org.unicitylabs.sdk.api.bft.trustbase;

/** The release table has no pin for the network. */
public class UnknownNetworkException extends TrustBaseException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public UnknownNetworkException(String message) {
    super(message);
  }
}
