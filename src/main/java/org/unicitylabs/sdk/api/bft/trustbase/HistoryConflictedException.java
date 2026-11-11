package org.unicitylabs.sdk.api.bft.trustbase;

/**
 * The session is conflicted by an authenticated fork after the release checkpoint: every append,
 * refresh and verification fails, and nothing is written. The evidence is
 * {@link #getConflict()}. The latch belongs to the live session only.
 */
public class HistoryConflictedException extends TrustBaseException {

  private final TrustBaseConflictException conflict;

  /**
   * Create the exception.
   *
   * @param conflict the fork that closed the session, with its evidence
   */
  public HistoryConflictedException(TrustBaseConflictException conflict) {
    super("The history is conflicted at epoch " + Long.toUnsignedString(conflict.getEpoch()) + ".",
        conflict);
    this.conflict = conflict;
  }

  /**
   * Get the fork that closed the session.
   *
   * @return the conflict, with its evidence
   */
  public TrustBaseConflictException getConflict() {
    return this.conflict;
  }
}
