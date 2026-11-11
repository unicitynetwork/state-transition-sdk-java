package org.unicitylabs.sdk.api.bft.trustbase;

/** The compare-and-swap lost the race on every attempt; retry later. */
public class CacheContendedException extends TrustBaseException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public CacheContendedException(String message) {
    super(message);
  }
}
