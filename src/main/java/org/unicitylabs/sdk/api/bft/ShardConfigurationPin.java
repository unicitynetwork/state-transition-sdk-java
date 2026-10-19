package org.unicitylabs.sdk.api.bft;

import java.util.Arrays;
import java.util.Objects;

/**
 * Pinned configuration hash of one shard, taken from the caller's own configuration.
 */
public final class ShardConfigurationPin {

  private final ShardId shard;
  private final byte[] hash;

  /**
   * Creates a pin.
   *
   * @param shard the shard
   * @param hash the 32-byte configuration hash
   * @throws IllegalArgumentException if the hash is not 32 bytes
   */
  public ShardConfigurationPin(ShardId shard, byte[] hash) {
    this.shard = Objects.requireNonNull(shard, "shard cannot be null");
    Objects.requireNonNull(hash, "hash cannot be null");
    if (hash.length != 32) {
      throw new IllegalArgumentException("Shard configuration hash must be 32 bytes.");
    }
    this.hash = Arrays.copyOf(hash, hash.length);
  }

  /**
   * The shard.
   *
   * @return shard id
   */
  public ShardId getShard() {
    return shard;
  }

  /**
   * The pinned hash.
   *
   * @return copy of the 32 bytes
   */
  public byte[] getHash() {
    return Arrays.copyOf(hash, hash.length);
  }
}
