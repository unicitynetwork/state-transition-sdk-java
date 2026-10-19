package org.unicitylabs.sdk.api.bft;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/**
 * Committed content of one shard in a {@link PartitionView}.
 *
 * <p>Immutable. The wire form is {@code [shardId, inputRecord, trHash, shardConfHash]}.
 */
public final class PartitionViewLeaf {

  private static final int HASH_LENGTH = 32;

  private final ShardId shardId;
  private final InputRecord inputRecord;
  private final byte[] technicalRecordHash;
  private final byte[] shardConfigurationHash;

  /**
   * Creates a leaf. The bounds of the leaf are checked when it is placed in a view.
   *
   * @param shardId the shard
   * @param inputRecord the shard's input record
   * @param technicalRecordHash technical record hash
   * @param shardConfigurationHash shard configuration hash
   */
  public PartitionViewLeaf(ShardId shardId, InputRecord inputRecord, byte[] technicalRecordHash,
      byte[] shardConfigurationHash) {
    this.shardId = Objects.requireNonNull(shardId, "shardId cannot be null");
    this.inputRecord = Objects.requireNonNull(inputRecord, "inputRecord cannot be null");
    Objects.requireNonNull(technicalRecordHash, "technicalRecordHash cannot be null");
    Objects.requireNonNull(shardConfigurationHash, "shardConfigurationHash cannot be null");
    this.technicalRecordHash = Arrays.copyOf(technicalRecordHash, technicalRecordHash.length);
    this.shardConfigurationHash =
        Arrays.copyOf(shardConfigurationHash, shardConfigurationHash.length);
  }

  /**
   * The shard.
   *
   * @return shard id
   */
  public ShardId getShardId() {
    return shardId;
  }

  /**
   * The shard's input record.
   *
   * @return input record
   */
  public InputRecord getInputRecord() {
    return inputRecord;
  }

  /**
   * Technical record hash.
   *
   * @return copy of the hash
   */
  public byte[] getTechnicalRecordHash() {
    return Arrays.copyOf(technicalRecordHash, technicalRecordHash.length);
  }

  /**
   * Shard configuration hash.
   *
   * @return copy of the hash
   */
  public byte[] getShardConfigurationHash() {
    return Arrays.copyOf(shardConfigurationHash, shardConfigurationHash.length);
  }

  /**
   * Strictly decodes one encoded leaf and checks its bounds.
   *
   * @param bytes CBOR {@code [shardId, inputRecord, trHash, shardConfHash]}
   * @return the leaf
   * @throws PartitionViewException if the bytes are not a valid leaf
   */
  public static PartitionViewLeaf fromCbor(byte[] bytes) {
    PartitionViewLeaf leaf = decode(bytes);
    leaf.validateBounds();
    return leaf;
  }

  /**
   * Canonical encoding.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        CborSerializer.encodeByteString(shardId.encode()),
        inputRecord.toCbor(),
        CborSerializer.encodeByteString(technicalRecordHash),
        CborSerializer.encodeByteString(shardConfigurationHash));
  }

  /** Decodes one leaf without checking its bounds beyond what allocation needs. */
  static PartitionViewLeaf decode(byte[] bytes) {
    try {
      List<byte[]> data = CborDeserializer.decodeArray(bytes, 4);
      byte[] shard = CborDeserializer.decodeByteString(data.get(0));
      // a shard id of the maximum length takes 33 bytes: check before decoding it
      if (shard.length > PartitionView.MAX_SHARD_ID_BITS / 8 + 1) {
        throw new PartitionViewException(PartitionViewException.Reason.BOUNDS,
            "Shard ID is longer than " + PartitionView.MAX_SHARD_ID_BITS + " bits.");
      }
      if (data.get(1).length > PartitionView.MAX_INPUT_RECORD_BYTES) {
        throw new PartitionViewException(PartitionViewException.Reason.BOUNDS,
            String.format("Input record is %d bytes, limit is %d.", data.get(1).length,
                PartitionView.MAX_INPUT_RECORD_BYTES));
      }
      return new PartitionViewLeaf(
          ShardId.decode(shard),
          InputRecord.fromCbor(data.get(1)),
          CborDeserializer.decodeByteString(data.get(2)),
          CborDeserializer.decodeByteString(data.get(3)));
    } catch (PartitionViewException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PartitionViewException(PartitionViewException.Reason.ENCODING,
          "Decoding partition view leaf: " + e.getMessage(), e);
    }
  }

  /**
   * Checks the per leaf limits. Does not know the expected topology.
   *
   * @throws PartitionViewException with reason BOUNDS if the leaf is out of bounds
   */
  void validateBounds() {
    if (shardId.getLength() > PartitionView.MAX_SHARD_ID_BITS) {
      throw bounds(String.format("Shard ID is %d bits, limit is %d.", shardId.getLength(),
          PartitionView.MAX_SHARD_ID_BITS));
    }

    // same rules as the native input record validation
    String structure = inputRecord.structureFailure();
    if (structure != null) {
      throw bounds("Input record: " + structure);
    }
    byte[] summary = inputRecord.getSummaryValue();
    int summaryLength = summary == null ? 0 : summary.length;
    if (summaryLength > PartitionView.MAX_SUMMARY_VALUE_BYTES) {
      throw bounds(String.format("Summary value is %d bytes, limit is %d.", summaryLength,
          PartitionView.MAX_SUMMARY_VALUE_BYTES));
    }
    checkHash("state hash", inputRecord.getHash());
    checkHash("previous hash", inputRecord.getPreviousHash());
    checkHash("block hash", inputRecord.getBlockHash());
    checkHash("executed transactions hash", inputRecord.getExecutedTransactionsHash());
    int irLength = inputRecord.toCbor().length;
    if (irLength > PartitionView.MAX_INPUT_RECORD_BYTES) {
      throw bounds(String.format("Input record is %d bytes, limit is %d.", irLength,
          PartitionView.MAX_INPUT_RECORD_BYTES));
    }
    if (technicalRecordHash.length != HASH_LENGTH) {
      throw bounds("TRHash must be " + HASH_LENGTH + " bytes.");
    }
    if (shardConfigurationHash.length != HASH_LENGTH) {
      throw bounds("ShardConfHash must be " + HASH_LENGTH + " bytes.");
    }
  }

  private static void checkHash(String name, byte[] value) {
    // absent/empty (initial state, unchanged state) or a full hash
    if (value != null && value.length != 0 && value.length != HASH_LENGTH) {
      throw bounds(String.format("%s must be empty or %d bytes, got %d.", name, HASH_LENGTH,
          value.length));
    }
  }

  private static PartitionViewException bounds(String message) {
    return new PartitionViewException(PartitionViewException.Reason.BOUNDS, message);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof PartitionViewLeaf)) {
      return false;
    }
    PartitionViewLeaf that = (PartitionViewLeaf) o;
    return shardId.equals(that.shardId) && inputRecord.equals(that.inputRecord)
        && Arrays.equals(technicalRecordHash, that.technicalRecordHash)
        && Arrays.equals(shardConfigurationHash, that.shardConfigurationHash);
  }

  @Override
  public int hashCode() {
    return Objects.hash(shardId, inputRecord, Arrays.hashCode(technicalRecordHash),
        Arrays.hashCode(shardConfigurationHash));
  }
}
