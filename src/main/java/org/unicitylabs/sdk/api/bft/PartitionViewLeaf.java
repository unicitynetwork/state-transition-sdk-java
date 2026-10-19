package org.unicitylabs.sdk.api.bft;

import java.util.Arrays;
import java.util.Objects;

/**
 * Committed content of one shard in a {@link PartitionView}.
 */
public final class PartitionViewLeaf {

  private final ShardId shardId;
  private final InputRecord inputRecord;
  private final byte[] technicalRecordHash;
  private final byte[] shardConfigurationHash;

  /**
   * Creates a leaf.
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
}
