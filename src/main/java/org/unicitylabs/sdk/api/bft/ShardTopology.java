package org.unicitylabs.sdk.api.bft;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validation of sharding schemes: complete prefix-free sets of shard ids in binary tree
 * left-to-right order.
 *
 * <p>Validation takes O(S) comparisons for an input that is already in canonical order and
 * O(S log S) otherwise (S shards of bounded width): a prefix relation between two ids of a sorted
 * list always shows between neighbors, so no pairwise scan is needed, and completeness is the Kraft
 * sum over the sorted ids.
 */
final class ShardTopology {

  private ShardTopology() {
  }

  /**
   * Shard ids of the scheme in canonical order. The empty scheme is the single empty shard id of
   * an unsharded partition.
   *
   * @param scheme the sharding scheme
   * @param maxLeaves limit of shards
   * @param maxBits limit of the id length in bits
   * @param comparisons optional counter of the comparisons made, one element; null for none
   * @return ids in canonical order
   * @throws PartitionViewException with reason TOPOLOGY if the scheme is not a complete
   *     prefix-free set within the limits
   */
  static List<ShardId> canonicalOrder(List<ShardId> scheme, int maxLeaves, int maxBits,
      long[] comparisons) {
    if (scheme.isEmpty()) {
      return Collections.singletonList(ShardId.empty());
    }
    if (scheme.size() > maxLeaves) {
      throw topology(String.format("Scheme has %d shards, limit is %d.", scheme.size(), maxLeaves));
    }
    // a single shard partition is the empty scheme, never a one element scheme
    if (scheme.size() == 1) {
      throw topology("Scheme can not contain a single shard.");
    }

    int maxLength = 0;
    for (ShardId id : scheme) {
      if (id.isEmpty()) {
        throw topology("Scheme may not contain the empty shard id.");
      }
      if (id.getLength() > maxBits) {
        throw topology(String.format("Shard ID %s is longer than %d bits.", id, maxBits));
      }
      maxLength = Math.max(maxLength, id.getLength());
    }

    // one pass tells a canonical input, which then needs no sort
    boolean sorted = true;
    for (int i = 1; i < scheme.size() && sorted; i++) {
      count(comparisons);
      sorted = ShardId.compareTreeOrder(scheme.get(i - 1), scheme.get(i)) <= 0;
    }
    List<ShardId> order = new ArrayList<>(scheme);
    if (!sorted) {
      order.sort((a, b) -> {
        count(comparisons);
        return ShardId.compareTreeOrder(a, b);
      });
    }

    for (int i = 1; i < order.size(); i++) {
      count(comparisons);
      if (order.get(i - 1).isPrefixOf(order.get(i))) {
        throw topology(String.format("Scheme is not prefix-free: %s is prefix of %s.",
                order.get(i - 1), order.get(i)));
      }
    }
    // a prefix-free scheme covers the whole tree iff the Kraft sum equals one
    BigInteger sum = BigInteger.ZERO;
    for (ShardId id : order) {
      sum = sum.add(BigInteger.ONE.shiftLeft(maxLength - id.getLength()));
    }
    if (!sum.equals(BigInteger.ONE.shiftLeft(maxLength))) {
      throw topology("Scheme does not cover the whole shard tree.");
    }
    return Collections.unmodifiableList(order);
  }

  private static void count(long[] comparisons) {
    if (comparisons != null) {
      comparisons[0]++;
    }
  }

  private static PartitionViewException topology(String message) {
    return new PartitionViewException(PartitionViewException.Reason.TOPOLOGY, message);
  }
}
