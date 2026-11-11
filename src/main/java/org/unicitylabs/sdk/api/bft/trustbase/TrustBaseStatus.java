package org.unicitylabs.sdk.api.bft.trustbase;

/** Status of a GetTrustBase response. The numbers are the wire values (bft-core {@code gettrustbase.Status}). */
public enum TrustBaseStatus {
  /** The page carries entries. */
  OK(0),
  /** The request was malformed. */
  INVALID_REQUEST(1),
  /** The source serves another network. */
  WRONG_NETWORK(2),
  /** The source has nothing from the requested epoch yet. */
  NOT_YET_AVAILABLE(3),
  /** The source is on another history than the echoed snapshot. */
  HISTORY_MISMATCH(4),
  /** The requested page size is below the source's minimum. */
  LIMIT_TOO_SMALL(5),
  /** The source is busy; ask again. */
  BUSY(6),
  /** The source cannot answer now; ask again. */
  UNAVAILABLE(7);

  private final int wire;

  TrustBaseStatus(int wire) {
    this.wire = wire;
  }

  /**
   * Get the wire value.
   *
   * @return the wire value
   */
  public int wire() {
    return this.wire;
  }

  /**
   * Get the status of a wire value.
   *
   * @param value the unsigned wire value
   * @return the status, or {@code null} for 8 and above
   */
  public static TrustBaseStatus fromWire(long value) {
    if (value < 0 || value >= values().length) {
      return null;
    }
    return values()[(int) value];
  }
}
