package org.unicitylabs.sdk.payment.asset;

import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.BitString;
import org.unicitylabs.sdk.util.HexConverter;

public class AssetId {
  private final byte[] bytes;

  public AssetId(byte[] bytes) {
    Objects.requireNonNull(bytes, "Asset id cannot be null");

    this.bytes = Arrays.copyOf(bytes, bytes.length);
  }

  public byte[] getBytes() {
    return Arrays.copyOf(this.bytes, this.bytes.length);
  }

  public static AssetId fromCbor(byte[] bytes) {
    return new AssetId(CborDeserializer.decodeByteString(bytes));
  }

  public BitString toBitString() {
    return new BitString(this.bytes);
  }

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

  public String toString() {
    return String.format("AssetId{bytes=%s}", HexConverter.encode(this.bytes));
  }
}
