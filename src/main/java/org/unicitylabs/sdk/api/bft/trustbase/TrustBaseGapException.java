package org.unicitylabs.sdk.api.bft.trustbase;

import org.unicitylabs.sdk.util.UnsignedLongs;

/** An entry is more than one epoch ahead of the tip: the entries in between must be fetched first. */
public class TrustBaseGapException extends TrustBaseException {

  private final long epoch;
  private final long tipEpoch;

  /**
   * Create the exception.
   *
   * @param epoch epoch of the offered entry, unsigned
   * @param tipEpoch epoch of the held tip, unsigned
   */
  public TrustBaseGapException(long epoch, long tipEpoch) {
    super("Epoch " + UnsignedLongs.toDecimal(epoch) + " is ahead of the tip "
        + UnsignedLongs.toDecimal(tipEpoch) + ".");
    this.epoch = epoch;
    this.tipEpoch = tipEpoch;
  }

  /**
   * Get the epoch of the offered entry.
   *
   * @return epoch, unsigned bits
   */
  public long getEpoch() {
    return this.epoch;
  }

  /**
   * Get the epoch of the held tip.
   *
   * @return epoch, unsigned bits
   */
  public long getTipEpoch() {
    return this.tipEpoch;
  }
}
