package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native shard tree of one partition, reconstructed from the complete leaf set.
 *
 * <p>The leaf hash is {@code H(IR, TRHash, ShardConfHash)}, an inner node is {@code H(left, right)}
 * with each child hash encoded as a CBOR byte string; the shard id only determines the tree
 * position. A single shard partition has a leaf as the root. The caller validates the topology
 * first; this class still refuses an incomplete or non-prefix-free leaf set.
 */
final class ShardTree {

  private static final int MAX_DEPTH = 256;

  private final Map<String, byte[]> nodes;

  private ShardTree(Map<String, byte[]> nodes) {
    this.nodes = nodes;
  }

  /**
   * Builds the tree.
   *
   * @param leaves leaves of all shards
   * @return the tree
   * @throws IllegalArgumentException if the leaves do not form a complete prefix-free tree
   */
  static ShardTree create(List<PartitionViewLeaf> leaves) {
    Map<String, byte[]> nodes = new HashMap<>();
    for (PartitionViewLeaf leaf : leaves) {
      String key = leaf.getShardId().toString();
      byte[] hash = UnicityCertificate.calculateShardLeafHash(
              leaf.getInputRecord(), leaf.getTechnicalRecordHash(),
              leaf.getShardConfigurationHash()).getData();
      if (nodes.put(key, hash) != null) {
        throw new IllegalArgumentException("Duplicate shard \"" + key + "\".");
      }
    }

    int leafCount = nodes.size();
    int[] reached = {0};
    generate("", nodes, reached);
    // every leaf must have been reached by the descent, otherwise a leaf below another was given
    if (reached[0] != leafCount) {
      throw new IllegalArgumentException("Shard tree leaves are not prefix-free.");
    }
    return new ShardTree(nodes);
  }

  private static byte[] generate(String prefix, Map<String, byte[]> nodes, int[] reached) {
    byte[] existing = nodes.get(prefix);
    if (existing != null) {
      reached[0]++;
      return existing;
    }
    if (prefix.length() >= MAX_DEPTH) {
      throw new IllegalArgumentException("Shard tree is not complete.");
    }
    byte[] left = generate(prefix + "0", nodes, reached);
    byte[] right = generate(prefix + "1", nodes, reached);
    byte[] hash = new DataHasher(HashAlgorithm.SHA256)
            .update(CborSerializer.encodeByteString(left))
            .update(CborSerializer.encodeByteString(right))
            .digest()
            .getData();
    nodes.put(prefix, hash);
    return hash;
  }

  /**
   * Root hash of the shard tree.
   *
   * @return copy of the root hash
   */
  byte[] rootHash() {
    byte[] root = nodes.get("");
    return Arrays.copyOf(root, root.length);
  }

  /**
   * Shard tree certificate (sibling hashes from the leaf to the root) of a shard.
   *
   * @param shard shard of a leaf
   * @return the certificate
   * @throws IllegalArgumentException if the shard is not a leaf of the tree
   */
  ShardTreeCertificate certificate(ShardId shard) {
    String key = shard.toString();
    if (!nodes.containsKey(key)) {
      throw new IllegalArgumentException("Shard \"" + key + "\" is not in the tree.");
    }
    List<byte[]> siblings = new ArrayList<>(key.length());
    for (int length = key.length(); length > 0; length--) {
      String prefix = key.substring(0, length - 1);
      String sibling = prefix + (key.charAt(length - 1) == '0' ? '1' : '0');
      siblings.add(nodes.get(sibling));
    }
    return new ShardTreeCertificate(shard, siblings);
  }
}
