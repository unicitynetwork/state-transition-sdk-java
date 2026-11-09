package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.util.UnsignedLongs;

/**
 * The typed "no trust-base entry for the seal's epoch" signal as the cause of a refusal that is
 * reported as an exception (a non-inclusion rejection, a consolidation failure). Nothing is wrong
 * with the proof as far as is known: the caller obtains the missing entries, appends them and
 * verifies again. It is carried as the cause, never as a reason of its own.
 */
public final class NoTrustBaseEntryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final long epoch;
  private final Long heldTipEpoch;

  /**
   * Create the signal.
   *
   * @param epoch epoch of the seal, unsigned bits
   * @param heldTipEpoch epoch of the held tip, null when the history is empty
   */
  public NoTrustBaseEntryException(long epoch, Long heldTipEpoch) {
    super("No trust-base entry for epoch " + UnsignedLongs.toDecimal(epoch) + " (held tip: "
        + (heldTipEpoch == null ? "none" : UnsignedLongs.toDecimal(heldTipEpoch)) + ").");
    this.epoch = epoch;
    this.heldTipEpoch = heldTipEpoch;
  }

  /**
   * Get the epoch the seal needs.
   *
   * @return epoch, unsigned bits
   */
  public long getEpoch() {
    return this.epoch;
  }

  /**
   * Get the epoch of the held tip.
   *
   * @return epoch, unsigned bits, or null when the history is empty
   */
  public Long getHeldTipEpoch() {
    return this.heldTipEpoch;
  }
}
