package org.unicitylabs.sdk.api;

import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.api.bft.ShardId;

/**
 * What the caller trusts about the shard and the authority when verifying non-inclusion. Immutable.
 *
 * <p>The default context is the unsharded partition (the empty shard), the same as the reference
 * verifier: a sharded caller names the shard it serves with {@link #withShard}. Optional pins are
 * stronger constraints from the caller&rsquo;s own configuration, never taken from a response.
 */
public final class NonInclusionContext {

  private static final NonInclusionContext DEFAULT =
      new NonInclusionContext(ShardId.decode(new byte[] {(byte) 0x80}), null, false, 0L);

  private final ShardId shard;
  private final byte[] shardConfigurationHash;
  private final boolean hasAuthorityEnd;
  private final long authorityEndRound;

  private NonInclusionContext(ShardId shard, byte[] shardConfigurationHash,
      boolean hasAuthorityEnd, long authorityEndRound) {
    this.shard = shard;
    this.shardConfigurationHash = shardConfigurationHash;
    this.hasAuthorityEnd = hasAuthorityEnd;
    this.authorityEndRound = authorityEndRound;
  }

  /**
   * The unsharded context: empty shard, no pins.
   *
   * @return default context
   */
  public static NonInclusionContext unsharded() {
    return DEFAULT;
  }

  /**
   * Requires the certificate of exactly this shard, with its complete shard path.
   *
   * @param shard native shard identifier; the empty shard for the unsharded partition
   * @return new context
   */
  public NonInclusionContext withShard(ShardId shard) {
    Objects.requireNonNull(shard, "shard cannot be null");
    return new NonInclusionContext(shard, shardConfigurationHash, hasAuthorityEnd,
        authorityEndRound);
  }

  /**
   * Pins the shard configuration hash the certificate must commit to.
   *
   * @param hash 32-byte hash
   * @return new context
   * @throws IllegalArgumentException if the hash is not 32 bytes
   */
  public NonInclusionContext withShardConfigurationHash(byte[] hash) {
    Objects.requireNonNull(hash, "hash cannot be null");
    if (hash.length != 32) {
      throw new IllegalArgumentException("Shard configuration hash must be 32 bytes.");
    }
    return new NonInclusionContext(shard, Arrays.copyOf(hash, 32), hasAuthorityEnd,
        authorityEndRound);
  }

  /**
   * Sets the authenticated end of the authority&rsquo;s epoch: a seal at or after this root chain
   * round is rejected.
   *
   * @param endRound first root chain round no longer covered by the authority (unsigned bits)
   * @return new context
   */
  public NonInclusionContext withAuthorityEndRound(long endRound) {
    return new NonInclusionContext(shard, shardConfigurationHash, true, endRound);
  }

  /**
   * Shard the verifier serves.
   *
   * @return shard id
   */
  public ShardId getShard() {
    return shard;
  }

  /**
   * Pinned shard configuration hash.
   *
   * @return copy of the hash, null if not pinned
   */
  public byte[] getShardConfigurationHash() {
    return shardConfigurationHash == null ? null
        : Arrays.copyOf(shardConfigurationHash, shardConfigurationHash.length);
  }

  /**
   * Tells if an authority end round is set.
   *
   * @return true if set
   */
  public boolean hasAuthorityEndRound() {
    return hasAuthorityEnd;
  }

  /**
   * End round of the authority (unsigned bits), meaningful only if {@link #hasAuthorityEndRound}.
   *
   * @return round bits
   */
  public long getAuthorityEndRound() {
    return authorityEndRound;
  }
}
