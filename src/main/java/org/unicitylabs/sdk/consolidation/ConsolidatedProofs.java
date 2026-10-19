package org.unicitylabs.sdk.consolidation;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.util.UnsignedLongs;

/**
 * Proofs rebuilt under one master seal: the original certification data and reference time of every
 * input under a new unicity certificate, in input order.
 */
public final class ConsolidatedProofs {

  private final ConsolidationCounters counters;
  private final long masterRound;
  private final ShardId masterShard;
  private final int requests;
  private final long requiredRound;
  private final List<InclusionProofResponse> responses;
  private final int shards;
  private final int waves;

  ConsolidatedProofs(ConsolidationCounters counters, long masterRound, ShardId masterShard,
      int requests, long requiredRound, List<InclusionProofResponse> responses, int shards,
      int waves) {
    this.counters = Objects.requireNonNull(counters);
    this.masterRound = masterRound;
    this.masterShard = Objects.requireNonNull(masterShard);
    this.requests = requests;
    this.requiredRound = requiredRound;
    this.responses = Collections.unmodifiableList(List.copyOf(responses));
    this.shards = shards;
    this.waves = waves;
  }

  /**
   * Work counters.
   *
   * @return counters
   */
  public ConsolidationCounters getCounters() {
    return counters;
  }

  /**
   * Root chain round of the master seal, as unsigned 64-bit bits.
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
   * HTTP requests made, the one retry without a wait included.
   *
   * @return requests
   */
  public int getRequests() {
    return requests;
  }

  /**
   * Highest root chain round among the original proofs (R_min), as unsigned 64-bit bits.
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
   * Replacement proofs in input order.
   *
   * @return unmodifiable list
   */
  public List<InclusionProofResponse> getResponses() {
    return responses;
  }

  /**
   * Shards that took part.
   *
   * @return count
   */
  public int getShards() {
    return shards;
  }

  /**
   * Dependency waves: 1 when a single shard takes part, otherwise 2.
   *
   * @return waves
   */
  public int getWaves() {
    return waves;
  }
}
