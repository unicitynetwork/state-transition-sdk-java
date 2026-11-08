package org.unicitylabs.sdk.api.bft.trustbase;

/** Budget of a page decode; the byte bound is checked before anything is read. */
public final class TrustBaseRangeLimits {

  /** The page caps of the GetTrustBase protocol (bft-core {@code gettrustbase.PageLimits}): 1 MiB, 64 entries. */
  public static final TrustBaseRangeLimits PAGE = new TrustBaseRangeLimits(1 << 20, 64);

  private final int maxBytes;
  private final int maxEntries;

  /**
   * Create a budget.
   *
   * @param maxBytes most bytes of a response
   * @param maxEntries most entries of a response
   */
  public TrustBaseRangeLimits(int maxBytes, int maxEntries) {
    if (maxBytes <= 0 || maxEntries <= 0) {
      throw new IllegalArgumentException("The limits must be positive.");
    }
    this.maxBytes = maxBytes;
    this.maxEntries = maxEntries;
  }

  /**
   * Get the byte bound.
   *
   * @return most bytes of a response
   */
  public int getMaxBytes() {
    return this.maxBytes;
  }

  /**
   * Get the entry bound.
   *
   * @return most entries of a response
   */
  public int getMaxEntries() {
    return this.maxEntries;
  }
}
