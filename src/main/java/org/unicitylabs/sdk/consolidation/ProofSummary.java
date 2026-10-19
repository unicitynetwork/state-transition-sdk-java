package org.unicitylabs.sdk.consolidation;

import java.math.BigInteger;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.util.UnsignedLongs;

/**
 * Diagnostics of a successful consolidation: counts of work and of requests, never timings.
 */
public final class ProofSummary {

  private final boolean alreadyConsolidated;
  private final int consolidatedSeals;
  private final long evidenceQuorumChecks;
  private final long masterRound;
  private final ShardId masterShard;
  private final int originalSeals;
  private final int outerProofs;
  private final long quorumChecks;
  private final int requests;
  private final long requiredRound;
  private final int shards;

  ProofSummary(boolean alreadyConsolidated, int consolidatedSeals, long evidenceQuorumChecks,
      long masterRound, ShardId masterShard, int originalSeals, int outerProofs,
      long quorumChecks, int requests, long requiredRound, int shards) {
    this.alreadyConsolidated = alreadyConsolidated;
    this.consolidatedSeals = consolidatedSeals;
    this.evidenceQuorumChecks = evidenceQuorumChecks;
    this.masterRound = masterRound;
    this.masterShard = masterShard;
    this.originalSeals = originalSeals;
    this.outerProofs = outerProofs;
    this.quorumChecks = quorumChecks;
    this.requests = requests;
    this.requiredRound = requiredRound;
    this.shards = shards;
  }

  /**
   * True when the outer proofs already shared one seal and the token was returned without any
   * network request.
   *
   * @return already consolidated
   */
  public boolean isAlreadyConsolidated() {
    return alreadyConsolidated;
  }

  /**
   * Distinct complete seals among the returned token's outer proofs (1 when consolidated).
   *
   * @return count
   */
  public int getConsolidatedSeals() {
    return consolidatedSeals;
  }

  /**
   * Quorum checks spent authenticating the original outer proofs.
   *
   * @return count
   */
  public long getEvidenceQuorumChecks() {
    return evidenceQuorumChecks;
  }

  /**
   * Root chain round of the master seal, unsigned bits.
   *
   * @return round bits
   */
  public long getMasterRound() {
    return masterRound;
  }

  /**
   * Root chain round of the master seal as a non-negative integer.
   *
   * @return round
   */
  public BigInteger getMasterRoundUnsigned() {
    return UnsignedLongs.toBigInteger(masterRound);
  }

  /**
   * Root chain round of the master seal in unsigned decimal.
   *
   * @return round
   */
  public String getMasterRoundDecimal() {
    return UnsignedLongs.toDecimal(masterRound);
  }

  /**
   * The master shard.
   *
   * @return shard id
   */
  public ShardId getMasterShard() {
    return masterShard;
  }

  /**
   * Distinct complete seals among the original outer proofs.
   *
   * @return count
   */
  public int getOriginalSeals() {
    return originalSeals;
  }

  /**
   * Number of replaceable proofs in the outer history (genesis and every transfer).
   *
   * @return count
   */
  public int getOuterProofs() {
    return outerProofs;
  }

  /**
   * Quorum signature checks of the master seal and the consolidated outer history.
   *
   * @return count
   */
  public long getQuorumChecks() {
    return quorumChecks;
  }

  /**
   * HTTP requests made (m, in two waves; one when m is 1).
   *
   * @return requests
   */
  public int getRequests() {
    return requests;
  }

  /**
   * Highest root chain round among the original proofs (R_min), unsigned bits.
   *
   * @return round bits
   */
  public long getRequiredRound() {
    return requiredRound;
  }

  /**
   * R_min as a non-negative integer.
   *
   * @return round
   */
  public BigInteger getRequiredRoundUnsigned() {
    return UnsignedLongs.toBigInteger(requiredRound);
  }

  /**
   * R_min in unsigned decimal.
   *
   * @return round
   */
  public String getRequiredRoundDecimal() {
    return UnsignedLongs.toDecimal(requiredRound);
  }

  /**
   * Shards that took part.
   *
   * @return count
   */
  public int getShards() {
    return shards;
  }
}
