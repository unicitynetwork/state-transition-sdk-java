package org.unicitylabs.sdk.api.bft.verification;

/**
 * Work counters of an {@link OperationUnicityCertificateVerifier}.
 */
public final class OperationVerificationStats {

  private final long authorityBuilds;
  private final long bodyChecks;
  private final long bodyHits;
  private final long encodes;
  private final long quorumChecks;

  /**
   * Creates the counters.
   *
   * @param authorityBuilds authority snapshots built
   * @param bodyChecks certificate body checks performed
   * @param bodyHits body checks served from the memo or a running check
   * @param encodes certificate encodings made for memo keys
   * @param quorumChecks quorum checks performed by the sessions
   */
  public OperationVerificationStats(long authorityBuilds, long bodyChecks, long bodyHits,
      long encodes, long quorumChecks) {
    this.authorityBuilds = authorityBuilds;
    this.bodyChecks = bodyChecks;
    this.bodyHits = bodyHits;
    this.encodes = encodes;
    this.quorumChecks = quorumChecks;
  }

  /**
   * Authority snapshots built.
   *
   * @return count
   */
  public long getAuthorityBuilds() {
    return authorityBuilds;
  }

  /**
   * Body checks performed.
   *
   * @return count
   */
  public long getBodyChecks() {
    return bodyChecks;
  }

  /**
   * Body checks that did not run.
   *
   * @return count
   */
  public long getBodyHits() {
    return bodyHits;
  }

  /**
   * Certificate encodings for memo keys.
   *
   * @return count
   */
  public long getEncodes() {
    return encodes;
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
