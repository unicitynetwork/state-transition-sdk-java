package org.unicitylabs.sdk.payment.asset;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.BigIntegerConverter;

public class Asset {

  private final BigInteger value;
  private final AssetId id;

  public Asset(AssetId id, BigInteger value) {
    this.id = id;
    this.value = value;
  }

  public AssetId getId() {
    return this.id;
  }

  public BigInteger getValue() {
    return this.value;
  }

  public static Asset fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new Asset(
        AssetId.fromCbor(data.get(0)),
        BigIntegerConverter.decode(CborDeserializer.decodeByteString(data.get(1)))
    );
  }

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
    return Objects.equals(this.value, asset.value) && Objects.equals(this.id, asset.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.value, this.id);
  }

  public String toString() {
    return String.format("Asset{value=%s, id=%s}", this.value, this.id);
  }
}
