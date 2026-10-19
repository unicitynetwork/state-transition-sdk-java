package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Unicity tree certificate.
 */
public class UnicityTreeCertificate {
  public static final long CBOR_TAG = 39004;
  private static final int VERSION = 1;

  private final int partitionIdentifier;
  private final List<HashStep> steps;

  UnicityTreeCertificate(
          int partitionIdentifier,
          List<HashStep> steps
  ) {
    Objects.requireNonNull(steps, "Steps cannot be null");

    this.partitionIdentifier = partitionIdentifier;
    this.steps = List.copyOf(steps); // rejects null steps
  }

  /**
   * Get certificate version.
   *
   * @return version
   */
  public int getVersion() {
    return UnicityTreeCertificate.VERSION;
  }

  /**
   * Get partition identifier as the raw bits of the unsigned 32-bit wire value: an identifier of
   * 2^31 or above reads as a negative {@code int}. Use {@link #getPartitionIdentifierUnsigned()}
   * for the number; comparing this value with a partition constant is wrong in general.
   *
   * @return partition identifier bits
   */
  public int getPartitionIdentifier() {
    return this.partitionIdentifier;
  }

  /**
   * Get partition identifier as the unsigned 32-bit number it is on the wire (0..2^32-1).
   *
   * @return partition identifier
   */
  public long getPartitionIdentifierUnsigned() {
    return Integer.toUnsignedLong(this.partitionIdentifier);
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
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != UnicityTreeCertificate.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), 3);

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != UnicityTreeCertificate.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return new UnicityTreeCertificate(
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
            UnicityTreeCertificate.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(UnicityTreeCertificate.VERSION),
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
    return Objects.equals(this.partitionIdentifier, that.partitionIdentifier)
            && Objects.equals(this.steps, that.steps);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.partitionIdentifier, this.steps);
  }

  @Override
  public String toString() {
    return String.format("UnicityTreeCertificate{partitionIdentifier=%s, steps=%s}",
            this.getPartitionIdentifierUnsigned(), this.steps);
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
     * Get key as the raw bits of the unsigned 32-bit wire value: a key of 2^31 or above reads as a
     * negative {@code int}. Use {@link #getKeyUnsigned()} for the number.
     *
     * @return key bits
     */
    public int getKey() {
      return this.key;
    }

    /**
     * Get key as the unsigned 32-bit number it is on the wire (0..2^32-1).
     *
     * @return key
     */
    public long getKeyUnsigned() {
      return Integer.toUnsignedLong(this.key);
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
      List<byte[]> data = CborDeserializer.decodeArray(bytes, 2);

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
      return Objects.equals(this.key, hashStep.key)
              && Objects.deepEquals(this.hash, hashStep.hash);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.key, Arrays.hashCode(this.hash));
    }

    @Override
    public String toString() {
      return String.format("UnicityTreeCertificate.HashStep{key=%s, hash=%s}",
              this.getKeyUnsigned(), HexConverter.encode(this.hash));
    }
  }
}
