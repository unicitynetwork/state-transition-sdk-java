package org.unicitylabs.sdk.api.bft.trustbase;

/** A GetTrustBase response is not a complete, linked history from a genesis entry (status, cursor, range, links, end identity). */
public class TrustBaseBundleException extends TrustBaseException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public TrustBaseBundleException(String message) {
    super(message);
  }
}
