package org.unicitylabs.sdk.api.bft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

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
 * checked at allocation boundaries (before a size-dependent allocation or decode).
 *
 * <p>Immutable.
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

  private static final long MAX_PARTITION_ID = 0xFFFFFFFFL;
  private static final int VERSION = 1;

  private final long partitionId;
  private final List<PartitionViewLeaf> leaves;

  private PartitionView(long partitionId, List<PartitionViewLeaf> leaves) {
    this.partitionId = partitionId;
    this.leaves = Collections.unmodifiableList(new ArrayList<>(leaves));
  }

  /**
   * Creates a view from leaves in canonical order, checking the bounds of the view and of every
   * leaf. Topology is checked when the view is authenticated.
   *
   * @param partitionId nonzero unsigned 32-bit partition identifier
   * @param leaves 1..{@link #MAX_LEAVES} leaves
   * @return the view
   * @throws PartitionViewException with reason BOUNDS if a limit is exceeded
   */
  public static PartitionView of(long partitionId, List<PartitionViewLeaf> leaves) {
    Objects.requireNonNull(leaves, "leaves cannot be null");
    if (partitionId <= 0 || partitionId > MAX_PARTITION_ID) {
      throw bounds("Partition ID must be a nonzero 32-bit unsigned integer.");
    }
    if (leaves.isEmpty() || leaves.size() > MAX_LEAVES) {
      throw bounds(String.format("Leaf count must be 1..%d, got %d.", MAX_LEAVES, leaves.size()));
    }
    for (int i = 0; i < leaves.size(); i++) {
      try {
        Objects.requireNonNull(leaves.get(i), "leaf cannot be null").validateBounds();
      } catch (PartitionViewException e) {
        throw new PartitionViewException(e.getReason(), "Leaf " + i + ": " + e.getMessage(), e);
      }
    }
    return new PartitionView(partitionId, leaves);
  }

  /**
   * Strictly decodes a view: bounded input size, exact array lengths, no trailing bytes, no
   * indefinite length items, explicit version 1, untagged, and the encoding must be the canonical
   * one.
   *
   * @param bytes CBOR bytes
   * @return untrusted candidate view
   * @throws PartitionViewException if the bytes are not a valid view; never a generic null/index
   *     exception
   */
  public static PartitionView fromCbor(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes cannot be null");
    if (bytes.length > MAX_BYTES) {
      throw bounds(String.format("Partition view is %d bytes, limit is %d.", bytes.length,
          MAX_BYTES));
    }

    PartitionView view;
    try {
      // the declared length of the outer array is checked before the items are read
      if (arrayHeaderLength(bytes) != 3) {
        throw encoding("Partition view must be an array of 3 elements.");
      }
      List<byte[]> data = CborDeserializer.decodeArray(bytes, 3);
      long version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asLong();
      if (version != VERSION) {
        throw encoding("Unsupported partition view version " + Long.toUnsignedString(version) + ".");
      }
      long partitionId = CborDeserializer.decodeUnsignedInteger(data.get(1)).asLong();
      // the leaf count is checked before any leaf is read
      long declared = arrayHeaderLength(data.get(2));
      if (declared < 1 || declared > MAX_LEAVES) {
        throw bounds(String.format("Leaf count must be 1..%d, got %s.", MAX_LEAVES,
            declared < 0 ? "more than 2^31" : Long.toString(declared)));
      }
      List<byte[]> items = CborDeserializer.decodeArray(data.get(2));
      List<PartitionViewLeaf> leaves = new ArrayList<>(items.size());
      for (byte[] item : items) {
        leaves.add(PartitionViewLeaf.decode(item));
      }
      view = of(partitionId, leaves);
    } catch (PartitionViewException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PartitionViewException(PartitionViewException.Reason.ENCODING,
          "Decoding partition view: " + e.getMessage(), e);
    }

    // decoded fields have no other representation, but a mismatch would mean the decoder accepted
    // a non-canonical form
    if (!Arrays.equals(view.toCbor(), bytes)) {
      throw encoding("Partition view is not canonically encoded.");
    }
    return view;
  }

  /**
   * Partition identifier, a nonzero unsigned 32-bit number.
   *
   * @return partition id
   */
  public long getPartitionId() {
    return partitionId;
  }

  /**
   * Wire format version of the view.
   *
   * @return 1
   */
  public int getVersion() {
    return VERSION;
  }

  /**
   * Leaves in canonical shard order.
   *
   * @return unmodifiable list of leaves
   */
  public List<PartitionViewLeaf> getLeaves() {
    return leaves;
  }

  /**
   * The sharding scheme the leaves describe: empty for a single shard partition. Candidate data,
   * not authenticated by itself.
   *
   * @return shard ids in leaf order
   */
  public List<ShardId> getScheme() {
    if (leaves.size() == 1 && leaves.get(0).getShardId().isEmpty()) {
      return Collections.emptyList();
    }
    List<ShardId> scheme = new ArrayList<>(leaves.size());
    for (PartitionViewLeaf leaf : leaves) {
      scheme.add(leaf.getShardId());
    }
    return Collections.unmodifiableList(scheme);
  }

  /**
   * Shard ids of a scheme in binary tree left-to-right order (lexicographic bit string, 0 before
   * 1). The empty scheme yields the single empty shard id.
   *
   * @param scheme sharding scheme
   * @return shard ids in canonical order
   * @throws PartitionViewException with reason TOPOLOGY if the scheme is not a complete
   *     prefix-free set within the limits
   */
  public static List<ShardId> canonicalShardOrder(List<ShardId> scheme) {
    return ShardTopology.canonicalOrder(scheme, MAX_LEAVES, MAX_SHARD_ID_BITS, null);
  }

  /**
   * Canonical encoding.
   *
   * @return CBOR bytes
   * @throws PartitionViewException with reason BOUNDS if the encoding exceeds {@link #MAX_BYTES}
   */
  public byte[] toCbor() {
    byte[][] encodedLeaves = new byte[leaves.size()][];
    for (int i = 0; i < encodedLeaves.length; i++) {
      encodedLeaves[i] = leaves.get(i).toCbor();
    }
    byte[] bytes = CborSerializer.encodeArray(
        CborSerializer.encodeUnsignedInteger(VERSION),
        CborSerializer.encodeUnsignedInteger(partitionId),
        CborSerializer.encodeArray(encodedLeaves));
    if (bytes.length > MAX_BYTES) {
      throw bounds(String.format("Encoded partition view is %d bytes, limit is %d.", bytes.length,
          MAX_BYTES));
    }
    return bytes;
  }

  /**
   * Validates the structure of the candidate for a partition: its leaves are distinct valid shard
   * ids forming a complete prefix-free scheme in canonical left-to-right order. The scheme is taken
   * from the candidate itself (it is authenticated later by the seal commitment); the pins of the
   * policy only narrow it. Does not touch any hash tree.
   *
   * @param partition expected partition id
   * @param policy optional pins
   * @throws PartitionViewException TOPOLOGY, or POLICY for a configuration hash that does not
   *     match its pin
   */
  void validateStructure(long partition, PartitionViewPolicy policy) {
    if (partitionId != partition) {
      throw new PartitionViewException(PartitionViewException.Reason.POLICY,
          String.format("Partition view is for partition %d, expected %d.", partitionId,
              partition));
    }
    List<ShardId> order = canonicalShardOrder(getScheme());
    if (policy.getScheme() != null) {
      List<ShardId> pinned = canonicalShardOrder(policy.getScheme());
      if (!pinned.equals(order)) {
        throw new PartitionViewException(PartitionViewException.Reason.POLICY,
            "Partition view scheme does not match the pinned scheme.");
      }
    }
    Set<ShardId> shards = new HashSet<>();
    for (PartitionViewLeaf leaf : leaves) {
      shards.add(leaf.getShardId());
    }
    for (ShardConfigurationPin pin : policy.getPins()) {
      if (!shards.contains(pin.getShard())) {
        throw new PartitionViewException(PartitionViewException.Reason.POLICY,
            String.format("Pinned shard \"%s\" is not a shard of the view.", pin.getShard()));
      }
    }
    for (int i = 0; i < leaves.size(); i++) {
      PartitionViewLeaf leaf = leaves.get(i);
      if (!leaf.getShardId().equals(order.get(i))) {
        throw new PartitionViewException(PartitionViewException.Reason.TOPOLOGY,
            String.format("Leaf %d is shard \"%s\", expected \"%s\".", i, leaf.getShardId(),
                order.get(i)));
      }
      String violation = policy.violation(leaf.getShardId(), leaf.getShardConfigurationHash());
      if (violation != null) {
        throw new PartitionViewException(PartitionViewException.Reason.POLICY, violation);
      }
    }
  }

  /**
   * Length of an array whose encoded header is at the start of the bytes, read without decoding the
   * items.
   *
   * @return the declared length, or -1 if it does not fit a 31 bit count
   */
  private static long arrayHeaderLength(byte[] bytes) {
    if (bytes.length == 0) {
      throw encoding("Empty input.");
    }
    int initial = bytes[0] & 0xff;
    if ((initial & 0xe0) != 0x80) {
      throw encoding("Expected an array.");
    }
    int info = initial & 0x1f;
    if (info < 24) {
      return info;
    }
    int width;
    switch (info) {
      case 24:
        width = 1;
        break;
      case 25:
        width = 2;
        break;
      case 26:
        width = 4;
        break;
      case 27:
        width = 8;
        break;
      default:
        throw encoding("Indefinite or reserved array length.");
    }
    if (bytes.length < 1 + width) {
      throw encoding("Premature end of data.");
    }
    long value = 0;
    for (int i = 0; i < width; i++) {
      value = (value << 8) | (bytes[1 + i] & 0xffL);
    }
    return width == 8 || value > Integer.MAX_VALUE || value < 0 ? -1 : value;
  }

  private static PartitionViewException bounds(String message) {
    return new PartitionViewException(PartitionViewException.Reason.BOUNDS, message);
  }

  private static PartitionViewException encoding(String message) {
    return new PartitionViewException(PartitionViewException.Reason.ENCODING, message);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof PartitionView)) {
      return false;
    }
    PartitionView that = (PartitionView) o;
    return partitionId == that.partitionId && leaves.equals(that.leaves);
  }

  @Override
  public int hashCode() {
    return Objects.hash(partitionId, leaves);
  }
}
