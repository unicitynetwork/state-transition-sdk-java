package org.unicitylabs.sdk.api.bft.trustbase;

/**
 * The stored cache is a valid history that must not be overwritten (over the budget, or an
 * authenticated chain diverging at or before the checkpoint): nothing is written; the application
 * decides.
 */
public class CacheRejectedException extends TrustBaseException {

  private final CacheRejection reason;

  /**
   * Create the exception.
   *
   * @param reason why the cache is kept
   */
  public CacheRejectedException(CacheRejection reason) {
    super("Cache rejected: " + reason + ".");
    this.reason = reason;
  }

  /**
   * Get why the cache is kept.
   *
   * @return the reason
   */
  public CacheRejection getReason() {
    return this.reason;
  }
}
