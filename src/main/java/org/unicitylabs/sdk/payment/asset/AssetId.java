package org.unicitylabs.sdk.payment.asset;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.BitString;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.Arrays;
import java.util.Objects;

/**
 * Unique identifier of an asset.
 */
public class AssetId {
  private final byte[] bytes;

  /**
   * Create asset id from bytes.
   *
   * @param bytes asset id bytes
   */
  public AssetId(byte[] bytes) {
    Objects.requireNonNull(bytes, "Asset id cannot be null");

    this.bytes = Arrays.copyOf(bytes, bytes.length);
  }

  /**
   * Get asset id bytes.
   *
   * @return asset id bytes
   */
  public byte[] getBytes() {
    return Arrays.copyOf(this.bytes, this.bytes.length);
  }

  /**
   * Deserialize asset id from CBOR bytes.
   *
   * @param bytes CBOR bytes
   *
   * @return asset id
   */
  public static AssetId fromCbor(byte[] bytes) {
    return new AssetId(CborDeserializer.decodeByteString(bytes));
  }

  /**
   * Convert asset id to bit string form used by sparse merkle trees.
   *
   * @return bit string
   */
  public BitString toBitString() {
    return BitString.fromBytes(this.bytes);
  }

  /**
   * Serialize asset id to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeByteString(this.bytes);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof AssetId)) {
      return false;
    }
    AssetId assetId = (AssetId) o;
    return Arrays.equals(this.bytes, assetId.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    return String.format("AssetId{bytes=%s}", HexConverter.encode(this.bytes));
  }
}
