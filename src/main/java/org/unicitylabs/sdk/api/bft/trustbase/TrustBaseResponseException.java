package org.unicitylabs.sdk.api.bft.trustbase;

/** A well-formed GetTrustBase response that breaks a consistency rule: network, status and cursor, start, order, links or end. */
public class TrustBaseResponseException extends TrustBaseException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public TrustBaseResponseException(String message) {
    super(message);
  }
}
