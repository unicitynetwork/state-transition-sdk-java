package org.unicitylabs.sdk.api.bft.trustbase;

import org.unicitylabs.sdk.util.UnsignedLongs;

/** A different entry is offered for an epoch the history already holds. */
public class TrustBaseConflictException extends TrustBaseException {

  private final long epoch;
  private final TrustBaseConflictDetails details;

  /**
   * Create the exception.
   *
   * @param epoch the epoch, unsigned
   * @param details bounded evidence, may be null
   */
  public TrustBaseConflictException(long epoch, TrustBaseConflictDetails details) {
    super("A different entry is held for epoch " + UnsignedLongs.toDecimal(epoch) + ".");
    this.epoch = epoch;
    this.details = details;
  }

  /**
   * Get the epoch.
   *
   * @return epoch, unsigned bits
   */
  public long getEpoch() {
    return this.epoch;
  }

  /**
   * Get the evidence.
   *
   * @return details, or null
   */
  public TrustBaseConflictDetails getDetails() {
    return this.details;
  }

  /**
   * Tell whether the offered entry is signed by a quorum of its predecessor.
   *
   * @return true if the evidence says it is authenticated
   */
  public boolean isAuthenticated() {
    return this.details != null && this.details.isAuthenticated();
  }
}
