package org.unicitylabs.sdk.payment.asset;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.BigIntegerConverter;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Represents an asset with an ID and a value.
 */
public final class Asset {

  private final BigInteger value;
  private final AssetId id;

  /**
   * Create a new asset with the given ID and value.
   *
   * @param id asset ID
   * @param value asset value
   */
  public Asset(AssetId id, BigInteger value) {
    this.id = Objects.requireNonNull(id, "Asset ID cannot be null");
    this.value = Objects.requireNonNull(value, "Asset value cannot be null");

    if (this.value.compareTo(BigInteger.ZERO) < 0) {
      throw new IllegalArgumentException("Asset value cannot be negative");
    }
  }

  /**
   * Get asset ID.
   *
   * @return asset ID
   */
  public AssetId getId() {
    return this.id;
  }

  /**
   * Get asset value.
   *
   * @return asset value
   */
  public BigInteger getValue() {
    return this.value;
  }

  /**
   * Deserialize asset from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return asset
   */
  public static Asset fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new Asset(
            AssetId.fromCbor(data.get(0)),
            BigIntegerConverter.decode(CborDeserializer.decodeByteString(data.get(1)))
    );
  }

  /**
   * Serialize asset to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
            this.id.toCbor(),
            CborSerializer.encodeByteString(BigIntegerConverter.encode(this.value))
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Asset)) {
      return false;
    }
    Asset asset = (Asset) o;
    return Objects.equals(this.id, asset.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.id);
  }

  @Override
  public String toString() {
    return String.format("Asset{value=%s, id=%s}", this.value, this.id);
  }
}
