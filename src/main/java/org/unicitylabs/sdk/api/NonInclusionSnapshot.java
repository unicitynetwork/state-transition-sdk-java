package org.unicitylabs.sdk.api;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.UnsignedLongs;

/**
 * The authenticated snapshot a non-inclusion verification speaks about. Every field is read from
 * the authenticated unicity certificate and input record, never from RPC metadata such as a block
 * number.
 *
 * <p>Rounds, epochs and the timestamp are unsigned 64-bit protocol numbers carried as raw
 * {@code long} bits: the plain getters return those bits; use the {@code Unsigned} (BigInteger) or
 * {@code Decimal} accessors for values at or above 2^63. Immutable; byte fields are copied.
 *
 * <p>Instances are created by {@link NonInclusionVerifier} only.
 */
public final class NonInclusionSnapshot {

  private final NetworkId networkId;
  private final long partitionId;
  private final ShardId shardId;
  private final long rootRound;
  private final long sealTimestampSeconds;
  private final long rootEpoch;
  private final long shardRound;
  private final long shardEpoch;
  private final byte[] stateRoot;

  NonInclusionSnapshot(NetworkId networkId, long partitionId, ShardId shardId, long rootRound,
      long sealTimestampSeconds, long rootEpoch, long shardRound, long shardEpoch,
      byte[] stateRoot) {
    this.networkId = Objects.requireNonNull(networkId);
    this.partitionId = partitionId;
    this.shardId = Objects.requireNonNull(shardId);
    this.rootRound = rootRound;
    this.sealTimestampSeconds = sealTimestampSeconds;
    this.rootEpoch = rootEpoch;
    this.shardRound = shardRound;
    this.shardEpoch = shardEpoch;
    this.stateRoot = stateRoot == null ? null : Arrays.copyOf(stateRoot, stateRoot.length);
  }

  /**
   * Network of the seal.
   *
   * @return network id
   */
  public NetworkId getNetworkId() {
    return networkId;
  }

  /**
   * Partition of the unicity tree certificate (always the aggregation partition).
   *
   * @return partition id
   */
  public long getPartitionId() {
    return partitionId;
  }

  /**
   * Native shard identifier of the certificate; the empty shard for the unsharded partition.
   *
   * @return shard id
   */
  public ShardId getShardId() {
    return shardId;
  }

  /**
   * Root chain round of the seal (unsigned bits).
   *
   * @return round bits
   */
  public long getRootRound() {
    return rootRound;
  }

  /**
   * Root chain round as an unsigned number.
   *
   * @return round
   */
  public BigInteger getRootRoundUnsigned() {
    return UnsignedLongs.toBigInteger(rootRound);
  }

  /**
   * Root chain round as an unsigned decimal.
   *
   * @return decimal text
   */
  public String getRootRoundDecimal() {
    return UnsignedLongs.toDecimal(rootRound);
  }

  /**
   * Certified seal timestamp, Unix seconds (unsigned bits). A consensus-certified time, not proof
   * of real-time freshness.
   *
   * @return timestamp bits
   */
  public long getSealTimestampSeconds() {
    return sealTimestampSeconds;
  }

  /**
   * Seal timestamp as an unsigned number.
   *
   * @return seconds
   */
  public BigInteger getSealTimestampSecondsUnsigned() {
    return UnsignedLongs.toBigInteger(sealTimestampSeconds);
  }

  /**
   * Seal timestamp as an unsigned decimal.
   *
   * @return decimal text
   */
  public String getSealTimestampSecondsDecimal() {
    return UnsignedLongs.toDecimal(sealTimestampSeconds);
  }

  /**
   * Root epoch of the seal (unsigned bits).
   *
   * @return epoch bits
   */
  public long getRootEpoch() {
    return rootEpoch;
  }

  /**
   * Root epoch as an unsigned number.
   *
   * @return epoch
   */
  public BigInteger getRootEpochUnsigned() {
    return UnsignedLongs.toBigInteger(rootEpoch);
  }

  /**
   * Root epoch as an unsigned decimal.
   *
   * @return decimal text
   */
  public String getRootEpochDecimal() {
    return UnsignedLongs.toDecimal(rootEpoch);
  }

  /**
   * Shard round of the input record (unsigned bits).
   *
   * @return round bits
   */
  public long getShardRound() {
    return shardRound;
  }

  /**
   * Shard round as an unsigned number.
   *
   * @return round
   */
  public BigInteger getShardRoundUnsigned() {
    return UnsignedLongs.toBigInteger(shardRound);
  }

  /**
   * Shard round as an unsigned decimal.
   *
   * @return decimal text
   */
  public String getShardRoundDecimal() {
    return UnsignedLongs.toDecimal(shardRound);
  }

  /**
   * Shard epoch of the input record (unsigned bits).
   *
   * @return epoch bits
   */
  public long getShardEpoch() {
    return shardEpoch;
  }

  /**
   * Shard epoch as an unsigned number.
   *
   * @return epoch
   */
  public BigInteger getShardEpochUnsigned() {
    return UnsignedLongs.toBigInteger(shardEpoch);
  }

  /**
   * Shard epoch as an unsigned decimal.
   *
   * @return decimal text
   */
  public String getShardEpochDecimal() {
    return UnsignedLongs.toDecimal(shardEpoch);
  }

  /**
   * The certified state root.
   *
   * @return copy of the 32-byte root; null for the certified empty tree
   */
  public byte[] getStateRoot() {
    return stateRoot == null ? null : Arrays.copyOf(stateRoot, stateRoot.length);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof NonInclusionSnapshot)) {
      return false;
    }
    NonInclusionSnapshot that = (NonInclusionSnapshot) o;
    return networkId.equals(that.networkId) && partitionId == that.partitionId
        && shardId.equals(that.shardId) && rootRound == that.rootRound
        && sealTimestampSeconds == that.sealTimestampSeconds && rootEpoch == that.rootEpoch
        && shardRound == that.shardRound && shardEpoch == that.shardEpoch
        && Arrays.equals(stateRoot, that.stateRoot);
  }

  @Override
  public int hashCode() {
    return Objects.hash(networkId, partitionId, shardId, rootRound, sealTimestampSeconds,
        rootEpoch, shardRound, shardEpoch, Arrays.hashCode(stateRoot));
  }

  @Override
  public String toString() {
    return "NonInclusionSnapshot{network=" + networkId + ", partition=" + partitionId
        + ", rootRound=" + getRootRoundDecimal() + ", rootEpoch=" + getRootEpochDecimal()
        + ", shardRound=" + getShardRoundDecimal() + ", stateRoot="
        + (stateRoot == null ? "empty" : HexConverter.encode(stateRoot)) + "}";
  }
}
