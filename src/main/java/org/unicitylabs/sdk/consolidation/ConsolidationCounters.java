package org.unicitylabs.sdk.consolidation;

/**
 * Work counters of an attempt, counts and never timings. {@code quorumChecks} is the master seal's
 * quorum check; the seal checks of the original proofs are reported separately.
 */
public final class ConsolidationCounters {

  private final long bodyChecks;
  private final long evidenceBodyChecks;
  private final long leafChecks;
  private final long evidenceQuorumChecks;
  private final long quorumChecks;
  private final long viewBytes;

  /**
   * Creates the counters.
   *
   * @param bodyChecks shard and partition path evaluations of the replacement certificates, one per
   *     participating shard
   * @param evidenceBodyChecks path evaluations of the original proofs
   * @param leafChecks leaf and application checks of the replacement proofs, one per proof
   * @param evidenceQuorumChecks quorum checks spent authenticating the original proofs
   * @param quorumChecks quorum checks spent authenticating the master seal
   * @param viewBytes bytes of the partition view inspected (0 for a direct target)
   */
  public ConsolidationCounters(long bodyChecks, long evidenceBodyChecks, long leafChecks,
      long evidenceQuorumChecks, long quorumChecks, long viewBytes) {
    this.bodyChecks = bodyChecks;
    this.evidenceBodyChecks = evidenceBodyChecks;
    this.leafChecks = leafChecks;
    this.evidenceQuorumChecks = evidenceQuorumChecks;
    this.quorumChecks = quorumChecks;
    this.viewBytes = viewBytes;
  }

  /**
   * Path evaluations of the replacement certificates.
   *
   * @return count
   */
  public long getBodyChecks() {
    return bodyChecks;
  }

  /**
   * Path evaluations of the original proofs.
   *
   * @return count
   */
  public long getEvidenceBodyChecks() {
    return evidenceBodyChecks;
  }

  /**
   * Leaf checks of the replacement proofs.
   *
   * @return count
   */
  public long getLeafChecks() {
    return leafChecks;
  }

  /**
   * Quorum checks of the original proofs.
   *
   * @return count
   */
  public long getEvidenceQuorumChecks() {
    return evidenceQuorumChecks;
  }

  /**
   * Quorum checks of the master seal.
   *
   * @return count
   */
  public long getQuorumChecks() {
    return quorumChecks;
  }

  /**
   * Bytes of the partition view inspected.
   *
   * @return bytes
   */
  public long getViewBytes() {
    return viewBytes;
  }
}
