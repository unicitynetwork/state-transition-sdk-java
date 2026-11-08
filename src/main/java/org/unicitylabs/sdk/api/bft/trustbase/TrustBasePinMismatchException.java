package org.unicitylabs.sdk.api.bft.trustbase;

/**
 * An entry, a cache or an override disagrees with the release checkpoint (at or before the pinned
 * epoch), or a custom authority is not acceptable. The checkpoint selects authority; the session is
 * not poisoned.
 */
public class TrustBasePinMismatchException extends TrustBaseException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public TrustBasePinMismatchException(String message) {
    super(message);
  }
}
