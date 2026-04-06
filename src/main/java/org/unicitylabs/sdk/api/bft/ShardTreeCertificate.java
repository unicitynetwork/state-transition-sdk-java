package org.unicitylabs.sdk.api.bft;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Shard tree certificate.
 */
public class ShardTreeCertificate {

  private final byte[] shard;
  private final List<byte[]> siblingHashList;

  ShardTreeCertificate(byte[] shard, List<byte[]> siblingHashList) {
    Objects.requireNonNull(shard, "Shard cannot be null");
    Objects.requireNonNull(siblingHashList, "Sibling hash list cannot be null");

    this.shard = Arrays.copyOf(shard, shard.length);
    this.siblingHashList = siblingHashList.stream()
        .map(hash -> Arrays.copyOf(hash, hash.length))
        .collect(Collectors.toList());
  }

  /**
   * Get shard.
   *
   * @return shard
   */
  public byte[] getShard() {
    return Arrays.copyOf(this.shard, this.shard.length);
  }

  /**
   * Get sibling hash list.
   *
   * @return sibling hash list
   */
  public List<byte[]> getSiblingHashList() {
    return this.siblingHashList.stream()
        .map(hash -> Arrays.copyOf(hash, hash.length))
        .collect(Collectors.toList());
  }

  /**
   * Deserialize shard tree certificate from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return shard tree certificate
   */
  public static ShardTreeCertificate fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new ShardTreeCertificate(
        CborDeserializer.decodeByteString(data.get(0)),
        CborDeserializer.decodeArray(data.get(1)).stream()
            .map(CborDeserializer::decodeByteString)
            .collect(Collectors.toList())
    );
  }

  /**
   * Serialize shard tree certificate to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        CborSerializer.encodeByteString(this.shard),
        CborSerializer.encodeArray(
            this.siblingHashList.stream()
                .map(CborSerializer::encodeByteString)
                .toArray(byte[][]::new)
        )
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ShardTreeCertificate)) {
      return false;
    }
    ShardTreeCertificate that = (ShardTreeCertificate) o;
    return Objects.deepEquals(this.shard, that.shard) && Objects.equals(
        this.siblingHashList, that.siblingHashList);
  }

  @Override
  public int hashCode() {
    return Objects.hash(Arrays.hashCode(this.shard), this.siblingHashList);
  }

  @Override
  public String toString() {
    return String.format("ShardTreeCertificate{shard=%s, siblingHashList=%s}",
        HexConverter.encode(this.shard), this.siblingHashList);
  }
}
