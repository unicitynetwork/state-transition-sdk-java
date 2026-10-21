package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Unicity certificate.
 */
public class UnicityCertificate {
  public static final long CBOR_TAG = 39001;
  private static final int VERSION = 1;

  private final InputRecord inputRecord;
  private final byte[] technicalRecordHash;
  private final byte[] shardConfigurationHash;
  private final ShardTreeCertificate shardTreeCertificate;
  private final UnicityTreeCertificate unicityTreeCertificate;
  private final UnicitySeal unicitySeal;

  UnicityCertificate(
          InputRecord inputRecord,
          byte[] technicalRecordHash,
          byte[] shardConfigurationHash,
          ShardTreeCertificate shardTreeCertificate,
          UnicityTreeCertificate unicityTreeCertificate,
          UnicitySeal unicitySeal
  ) {
    Objects.requireNonNull(inputRecord, "Input record cannot be null");
    Objects.requireNonNull(shardConfigurationHash, "Shard configuration hash cannot be null");
    Objects.requireNonNull(shardTreeCertificate, "Shard tree certificate cannot be null");
    Objects.requireNonNull(unicityTreeCertificate, "Unicity tree certificate cannot be null");
    Objects.requireNonNull(unicitySeal, "Unicity seal cannot be null");

    this.inputRecord = inputRecord;
    this.technicalRecordHash = technicalRecordHash != null
            ? Arrays.copyOf(technicalRecordHash, technicalRecordHash.length)
            : null;
    this.shardConfigurationHash = Arrays.copyOf(
            shardConfigurationHash,
            shardConfigurationHash.length
    );
    this.shardTreeCertificate = shardTreeCertificate;
    this.unicityTreeCertificate = unicityTreeCertificate;
    this.unicitySeal = unicitySeal;
  }

  /**
   * Rule of the certificate body that a certificate violates, see {@link #checkBody()}.
   */
  public enum BodyViolation {
    /** The certificate is not of the aggregation partition. */
    PARTITION("The certificate is not of the aggregation partition."),
    /** The input record violates the native structure rules. */
    INPUT_RECORD("The input record is invalid."),
    /** The state root is present but not 32 bytes. */
    STATE_HASH_LENGTH("The state root must be absent or 32 bytes."),
    /** The technical record hash is absent or not 32 bytes. */
    TECHNICAL_RECORD_HASH_LENGTH("The technical record hash must be 32 bytes."),
    /** The shard path does not have exactly one sibling per bit of the shard id. */
    SHARD_PATH_LENGTH("The shard path must have one sibling per bit of the shard id.");

    private final String description;

    BodyViolation(String description) {
      this.description = description;
    }

    /**
     * Fixed description of the rule.
     *
     * @return description
     */
    public String getDescription() {
      return this.description;
    }
  }

  /**
   * Checks the properties of the certificate body that hold for every application use of a
   * certificate, before any hashing or signature work (bft-go-base {@code UnicityCertificate.IsValid}
   * parity). In order: the partition is the fixed {@link AggregationPartition#AGGREGATION_PARTITION_ID}
   * (so a memo can never carry another partition); the input record satisfies its conditional
   * structure rules ({@link InputRecord#structureFailure()}); the state root is absent or exactly
   * 32 bytes; the technical record hash is exactly 32 bytes; the shard path has exactly one
   * sibling per shard bit. Never throws on a decoded or constructed certificate.
   *
   * <p>The seal is not part of the body; its structure and authority are checked by the seal
   * session.
   *
   * @return the first violated rule, or null if the body is acceptable
   */
  public BodyViolation checkBody() {
    if (this.unicityTreeCertificate.getPartitionIdentifierUnsigned()
            != AggregationPartition.AGGREGATION_PARTITION_ID) {
      return BodyViolation.PARTITION;
    }
    if (this.inputRecord.structureFailure() != null) {
      return BodyViolation.INPUT_RECORD;
    }
    byte[] root = this.inputRecord.getHash();
    if (root != null && root.length != 32) {
      return BodyViolation.STATE_HASH_LENGTH;
    }
    if (this.technicalRecordHash == null || this.technicalRecordHash.length != 32) {
      return BodyViolation.TECHNICAL_RECORD_HASH_LENGTH;
    }
    if (this.shardTreeCertificate.getSiblingHashCount()
            != this.shardTreeCertificate.getShard().getLength()) {
      return BodyViolation.SHARD_PATH_LENGTH;
    }
    return null;
  }

  /**
   * Get the certificate version.
   *
   * @return certificate version
   */
  public int getVersion() {
    return UnicityCertificate.VERSION;
  }

  /**
   * Get the input record.
   *
   * @return input record
   */
  public InputRecord getInputRecord() {
    return this.inputRecord;
  }

  /**
   * Get the technical record hash.
   *
   * @return technical record hash
   */
  public byte[] getTechnicalRecordHash() {
    return this.technicalRecordHash != null
            ? Arrays.copyOf(this.technicalRecordHash, this.technicalRecordHash.length)
            : null;
  }

  /**
   * Get the shard configuration hash.
   *
   * @return shard configuration hash
   */
  public byte[] getShardConfigurationHash() {
    return Arrays.copyOf(this.shardConfigurationHash, this.shardConfigurationHash.length);
  }

  /**
   * Get the shard tree certificate.
   *
   * @return shard tree certificate
   */
  public ShardTreeCertificate getShardTreeCertificate() {
    return this.shardTreeCertificate;
  }

  /**
   * Get the unicity tree certificate.
   *
   * @return unicity tree certificate
   */
  public UnicityTreeCertificate getUnicityTreeCertificate() {
    return this.unicityTreeCertificate;
  }

  /**
   * Get the unicity seal.
   *
   * @return unicity seal
   */
  public UnicitySeal getUnicitySeal() {
    return this.unicitySeal;
  }

  /**
   * Calculate the shard tree leaf hash: the hash of the committed input record, technical record
   * hash and shard configuration hash.
   *
   * @param inputRecord            input record
   * @param technicalRecordHash    technical record hash, null if absent
   * @param shardConfigurationHash shard configuration hash
   * @return leaf hash
   */
  public static DataHash calculateShardLeafHash(
          InputRecord inputRecord,
          byte[] technicalRecordHash,
          byte[] shardConfigurationHash
  ) {
    return new DataHasher(HashAlgorithm.SHA256)
            .update(inputRecord.toCbor())
            .update(
                    CborSerializer.encodeNullable(technicalRecordHash, CborSerializer::encodeByteString))
            .update(CborSerializer.encodeByteString(shardConfigurationHash))
            .digest();
  }

  /**
   * Calculate the root hash of the shard tree certificate.
   *
   * @param inputRecord            input record
   * @param technicalRecordHash    technical record hash
   * @param shardConfigurationHash shard configuration hash
   * @param shardTreeCertificate   shard tree certificate
   * @return root hash
   * @throws IllegalArgumentException if the shard path does not have one sibling per shard bit
   */
  public static DataHash calculateShardTreeCertificateRootHash(
          InputRecord inputRecord,
          byte[] technicalRecordHash,
          byte[] shardConfigurationHash,
          ShardTreeCertificate shardTreeCertificate
  ) {

    ShardId shardId = shardTreeCertificate.getShard();
    List<byte[]> siblingHashes = shardTreeCertificate.getSiblingHashList();
    if (siblingHashes.size() != shardId.getLength()) {
      throw new IllegalArgumentException(
              "The shard path must have one sibling hash per bit of the shard id.");
    }

    DataHash rootHash = calculateShardLeafHash(
            inputRecord, technicalRecordHash, shardConfigurationHash);

    for (int i = 0; i < siblingHashes.size(); i++) {
      boolean isRight = shardId.getBit(shardId.getLength() - 1 - i) == 1;
      if (isRight) {
        rootHash = new DataHasher(HashAlgorithm.SHA256)
                .update(CborSerializer.encodeByteString(siblingHashes.get(i)))
                .update(CborSerializer.encodeByteString(rootHash.getData()))
                .digest();
      } else {
        rootHash = new DataHasher(HashAlgorithm.SHA256)
                .update(CborSerializer.encodeByteString(rootHash.getData()))
                .update(CborSerializer.encodeByteString(siblingHashes.get(i)))
                .digest();
      }
    }

    return rootHash;

  }

  /**
   * Deserialize unicity certificate from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return unicity certificate
   */
  public static UnicityCertificate fromCbor(byte[] bytes) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != UnicityCertificate.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), 7);

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != UnicityCertificate.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return new UnicityCertificate(
            InputRecord.fromCbor(data.get(1)),
            CborDeserializer.decodeNullable(data.get(2), CborDeserializer::decodeByteString),
            CborDeserializer.decodeByteString(data.get(3)),
            ShardTreeCertificate.fromCbor(data.get(4)),
            UnicityTreeCertificate.fromCbor(data.get(5)),
            UnicitySeal.fromCbor(data.get(6))
    );
  }

  /**
   * Serialize unicity certificate to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
            UnicityCertificate.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(UnicityCertificate.VERSION),
                    this.inputRecord.toCbor(),
                    CborSerializer.encodeNullable(this.technicalRecordHash,
                            CborSerializer::encodeByteString),
                    CborSerializer.encodeByteString(this.shardConfigurationHash),
                    this.shardTreeCertificate.toCbor(),
                    this.unicityTreeCertificate.toCbor(),
                    this.unicitySeal.toCbor()
            ));
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof UnicityCertificate)) {
      return false;
    }
    UnicityCertificate that = (UnicityCertificate) o;
    return Objects.equals(this.inputRecord, that.inputRecord)
            && Objects.deepEquals(this.technicalRecordHash, that.technicalRecordHash)
            && Objects.deepEquals(this.shardConfigurationHash, that.shardConfigurationHash)
            && Objects.equals(this.shardTreeCertificate, that.shardTreeCertificate)
            && Objects.equals(this.unicityTreeCertificate, that.unicityTreeCertificate)
            && Objects.equals(this.unicitySeal, that.unicitySeal);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.inputRecord, Arrays.hashCode(this.technicalRecordHash),
            Arrays.hashCode(this.shardConfigurationHash), this.shardTreeCertificate,
            this.unicityTreeCertificate, this.unicitySeal);
  }

  @Override
  public String toString() {
    return String.format("UnicityCertificate{inputRecord=%s, technicalRecordHash=%s, "
                    + "shardConfigurationHash=%s, shardTreeCertificate=%s, unicityTreeCertificate=%s, "
                    + "unicitySeal=%s}",
            this.inputRecord,
            this.technicalRecordHash != null ? HexConverter.encode(this.technicalRecordHash) : null,
            HexConverter.encode(this.shardConfigurationHash),
            this.shardTreeCertificate,
            this.unicityTreeCertificate,
            this.unicitySeal
    );
  }
}
