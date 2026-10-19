package org.unicitylabs.sdk.api.bft.verification;

/**
 * Work counters of a {@link SealVerificationSession}. Tests assert counts, not timings.
 */
public final class SealVerificationStats {

  private final long inFlightJoins;
  private final long memoHits;
  private final long quorumChecks;

  /**
   * Creates the counters.
   *
   * @param inFlightJoins callers that joined a verification already running
   * @param memoHits answers served from the memo of successful checks
   * @param quorumChecks quorum signature checks actually performed
   */
  public SealVerificationStats(long inFlightJoins, long memoHits, long quorumChecks) {
    this.inFlightJoins = inFlightJoins;
    this.memoHits = memoHits;
    this.quorumChecks = quorumChecks;
  }

  /**
   * Callers that joined a running verification.
   *
   * @return count
   */
  public long getInFlightJoins() {
    return inFlightJoins;
  }

  /**
   * Answers served from the memo.
   *
   * @return count
   */
  public long getMemoHits() {
    return memoHits;
  }

  /**
   * Quorum checks performed.
   *
   * @return count
   */
  public long getQuorumChecks() {
    return quorumChecks;
  }
}
