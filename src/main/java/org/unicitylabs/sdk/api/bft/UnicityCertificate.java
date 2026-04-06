package org.unicitylabs.sdk.api.bft;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer.CborTag;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Unicity certificate.
 */
public class UnicityCertificate {

  private final int version;
  private final InputRecord inputRecord;
  private final byte[] technicalRecordHash;
  private final byte[] shardConfigurationHash;
  private final ShardTreeCertificate shardTreeCertificate;
  private final UnicityTreeCertificate unicityTreeCertificate;
  private final UnicitySeal unicitySeal;

  UnicityCertificate(
      int version,
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

    this.version = version;
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
   * Get the certificate version.
   *
   * @return certificate version
   */
  public int getVersion() {
    return this.version;
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
   * Calculate the root hash of the shard tree certificate.
   *
   * @param inputRecord            input record
   * @param technicalRecordHash    technical record hash
   * @param shardConfigurationHash shard configuration hash
   * @param shardTreeCertificate   shard tree certificate
   * @return root hash
   */
  public static DataHash calculateShardTreeCertificateRootHash(
      InputRecord inputRecord,
      byte[] technicalRecordHash,
      byte[] shardConfigurationHash,
      ShardTreeCertificate shardTreeCertificate
  ) {

    DataHash rootHash = new DataHasher(HashAlgorithm.SHA256)
        .update(inputRecord.toCbor())
        .update(
            CborSerializer.encodeOptional(technicalRecordHash, CborSerializer::encodeByteString))
        .update(CborSerializer.encodeByteString(shardConfigurationHash))
        .digest();

    byte[] shardId = shardTreeCertificate.getShard();
    List<byte[]> siblingHashes = shardTreeCertificate.getSiblingHashList();
    for (int i = 0; i < siblingHashes.size(); i++) {
      boolean isRight = shardId[(shardId.length - 1) - (i / 8)] == 1;
      if (isRight) {
        rootHash = new DataHasher(HashAlgorithm.SHA256)
            .update(siblingHashes.get(i))
            .update(rootHash.getData())
            .digest();
      } else {
        rootHash = new DataHasher(HashAlgorithm.SHA256)
            .update(rootHash.getData())
            .update(siblingHashes.get(i))
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
    CborTag tag = CborDeserializer.decodeTag(bytes);
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData());

    return new UnicityCertificate(
        CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt(),
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
        1007,
        CborSerializer.encodeArray(
            CborSerializer.encodeUnsignedInteger(this.version),
            this.inputRecord.toCbor(),
            CborSerializer.encodeOptional(this.technicalRecordHash,
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
    return Objects.equals(this.version, that.version) && Objects.equals(this.inputRecord,
        that.inputRecord) && Objects.deepEquals(this.technicalRecordHash,
        that.technicalRecordHash) && Objects.deepEquals(this.shardConfigurationHash,
        that.shardConfigurationHash) && Objects.equals(this.shardTreeCertificate,
        that.shardTreeCertificate) && Objects.equals(this.unicityTreeCertificate,
        that.unicityTreeCertificate) && Objects.equals(this.unicitySeal, that.unicitySeal);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.version, this.inputRecord, Arrays.hashCode(this.technicalRecordHash),
        Arrays.hashCode(this.shardConfigurationHash), this.shardTreeCertificate,
        this.unicityTreeCertificate, this.unicitySeal);
  }

  @Override
  public String toString() {
    return String.format("UnicityCertificate{version=%s, inputRecord=%s, technicalRecordHash=%s, "
            + "shardConfigurationHash=%s, shardTreeCertificate=%s, unicityTreeCertificate=%s, "
            + "unicitySeal=%s}",
        this.version,
        this.inputRecord,
        this.technicalRecordHash != null ? HexConverter.encode(this.technicalRecordHash) : null,
        HexConverter.encode(this.shardConfigurationHash),
        this.shardTreeCertificate,
        this.unicityTreeCertificate,
        this.unicitySeal
    );
  }
}
