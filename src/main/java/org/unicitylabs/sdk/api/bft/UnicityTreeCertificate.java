package org.unicitylabs.sdk.api.bft;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer.CborTag;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Unicity tree certificate.
 */
public class UnicityTreeCertificate {

  private final int version;
  private final int partitionIdentifier;
  private final List<HashStep> steps;

  UnicityTreeCertificate(
      int version,
      int partitionIdentifier,
      List<HashStep> steps
  ) {
    Objects.requireNonNull(steps, "Steps cannot be null");

    this.version = version;
    this.partitionIdentifier = partitionIdentifier;
    this.steps = List.copyOf(steps);
  }

  /**
   * Get certificate version.
   *
   * @return version
   */
  public int getVersion() {
    return this.version;
  }

  /**
   * Get partition identifier.
   *
   * @return partition identifier
   */
  public int getPartitionIdentifier() {
    return this.partitionIdentifier;
  }

  /**
   * Get hash steps.
   *
   * @return hash steps
   */
  public List<HashStep> getSteps() {
    return this.steps;
  }

  /**
   * Deserialize certificate from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return certificate
   */
  public static UnicityTreeCertificate fromCbor(byte[] bytes) {
    CborTag tag = CborDeserializer.decodeTag(bytes);
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData());

    return new UnicityTreeCertificate(
        CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt(),
        CborDeserializer.decodeUnsignedInteger(data.get(1)).asInt(),
        CborDeserializer.decodeArray(data.get(2)).stream()
            .map(HashStep::fromCbor)
            .collect(Collectors.toList())
    );
  }

  /**
   * Serialize certificate to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
        1014,
        CborSerializer.encodeArray(
            CborSerializer.encodeUnsignedInteger(this.version),
            CborSerializer.encodeUnsignedInteger(this.partitionIdentifier),
            CborSerializer.encodeArray(this.steps.stream()
                .map(HashStep::toCbor)
                .toArray(byte[][]::new))
        ));
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof UnicityTreeCertificate)) {
      return false;
    }
    UnicityTreeCertificate that = (UnicityTreeCertificate) o;
    return Objects.equals(this.version, that.version) && Objects.equals(
        this.partitionIdentifier, that.partitionIdentifier) && Objects.equals(this.steps,
        that.steps);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.version, this.partitionIdentifier, this.steps);
  }

  @Override
  public String toString() {
    return String.format("UnicityTreeCertificate{version=%s, partitionIdentifier=%s, steps=%s",
        this.version, this.partitionIdentifier, this.steps);
  }

  /**
   * Hash step in the certificate.
   */
  public static class HashStep {

    private final int key;
    private final byte[] hash;

    HashStep(int key, byte[] hash) {
      Objects.requireNonNull(hash, "Hash cannot be null");

      this.key = key;
      this.hash = Arrays.copyOf(hash, hash.length);
    }

    /**
     * Get key.
     *
     * @return key
     */
    public int getKey() {
      return this.key;
    }

    /**
     * Get hash.
     *
     * @return hash
     */
    public byte[] getHash() {
      return Arrays.copyOf(this.hash, this.hash.length);
    }

    /**
     * Deserialize hash step from CBOR bytes.
     *
     * @param bytes CBOR bytes
     * @return hash step
     */
    public static HashStep fromCbor(byte[] bytes) {
      List<byte[]> data = CborDeserializer.decodeArray(bytes);

      return new HashStep(
          CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt(),
          CborDeserializer.decodeByteString(data.get(1))
      );
    }

    /**
     * Serialize hash step to CBOR bytes.
     *
     * @return CBOR bytes
     */
    public byte[] toCbor() {
      return CborSerializer.encodeArray(
          CborSerializer.encodeUnsignedInteger(this.key),
          CborSerializer.encodeByteString(this.hash)
      );
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof HashStep)) {
        return false;
      }
      HashStep hashStep = (HashStep) o;
      return Objects.equals(this.key, hashStep.key) && Objects.deepEquals(this.hash,
          hashStep.hash);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.key, Arrays.hashCode(this.hash));
    }

    @Override
    public String toString() {
      return String.format("UnicityTreeCertificate.HashStep{key=%s, hash=%s",
          this.key, HexConverter.encode(this.hash));
    }
  }
}
