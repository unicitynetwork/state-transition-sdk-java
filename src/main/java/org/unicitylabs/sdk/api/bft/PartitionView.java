package org.unicitylabs.sdk.api.bft;

import java.util.List;

/**
 * Partition view: the complete vector of shard tree leaves of one partition, as committed by a
 * single unicity seal. The ordinary unicity certificates of every shard can be derived from it.
 *
 * <p>A decoded view is an <em>untrusted candidate</em>: only
 * {@link PartitionViewAuthenticator#authenticate} turns it into an {@link AuthenticatedPartitionView}.
 * The wire form is untagged, explicitly versioned CBOR {@code [1, partitionId, [[shardId,
 * inputRecord, trHash, shardConfHash], ...]]}. Decoding is strict and bounded: exact
 * array/tag/version, canonical bytes, no trailing data, null versus empty preserved, and the limits
 * {@link #MAX_BYTES}, {@link #MAX_LEAVES}, {@link #MAX_SHARD_ID_BITS}, {@link #MAX_INPUT_RECORD_BYTES}
 * checked at allocation boundaries.
 */
public final class PartitionView {

  /** Largest encoded view in bytes. */
  public static final int MAX_BYTES = 16 * 1024;
  /** Largest encoded input record in bytes. */
  public static final int MAX_INPUT_RECORD_BYTES = 1024;
  /** Most leaves of a view. */
  public static final int MAX_LEAVES = 8;
  /** Longest shard id in bits. */
  public static final int MAX_SHARD_ID_BITS = 256;
  /** Longest summary value in bytes. */
  public static final int MAX_SUMMARY_VALUE_BYTES = 256;

  private PartitionView() {
  }

  /**
   * Strictly decodes a view.
   *
   * @param bytes canonical CBOR
   * @return untrusted candidate view
   * @throws PartitionViewException if the bytes are not a valid view; never a generic null/index
   *     exception
   */
  public static PartitionView fromCbor(byte[] bytes) {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Partition identifier, as unsigned bits (see {@code UnsignedLongs}).
   *
   * @return partition id
   */
  public long getPartitionId() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Leaves in canonical shard order.
   *
   * @return unmodifiable list of leaves
   */
  public List<PartitionViewLeaf> getLeaves() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Canonical encoding.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    throw new UnsupportedOperationException("Implemented by package A");
  }
}
