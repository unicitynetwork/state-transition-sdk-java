package org.unicitylabs.sdk.api.bft.trustbase;

/**
 * Budget of a bundle decode; a release supplies it, there is no fixed history length.
 * {@code maxBytes} is checked before anything is read; {@code maxEntries} from the array header
 * before any entry is read.
 */
public final class TrustBaseBundleLimits {

  private final int maxBytes;
  private final int maxEntries;
  private final int maxEntryBytes;

  /**
   * Create a budget with the default per-entry bound ({@link TrustBaseEntry#MAX_ENTRY_BYTES}).
   *
   * @param maxBytes most bytes of the whole bundle
   * @param maxEntries most entries
   */
  public TrustBaseBundleLimits(int maxBytes, int maxEntries) {
    this(maxBytes, maxEntries, TrustBaseEntry.MAX_ENTRY_BYTES);
  }

  /**
   * Create a budget.
   *
   * @param maxBytes most bytes of the whole bundle
   * @param maxEntries most entries
   * @param maxEntryBytes most bytes of one entry
   */
  public TrustBaseBundleLimits(int maxBytes, int maxEntries, int maxEntryBytes) {
    if (maxBytes <= 0 || maxEntries <= 0 || maxEntryBytes <= 0) {
      throw new IllegalArgumentException("Limits must be positive.");
    }
    this.maxBytes = maxBytes;
    this.maxEntries = maxEntries;
    this.maxEntryBytes = maxEntryBytes;
  }

  /** Get the byte budget of the whole bundle. */
  public int getMaxBytes() {
    return this.maxBytes;
  }

  /** Get the entry-count budget. */
  public int getMaxEntries() {
    return this.maxEntries;
  }

  /** Get the byte budget of one entry. */
  public int getMaxEntryBytes() {
    return this.maxEntryBytes;
  }
}
