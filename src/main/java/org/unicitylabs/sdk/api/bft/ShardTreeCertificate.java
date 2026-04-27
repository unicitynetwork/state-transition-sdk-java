package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Shard tree certificate.
 */
public class ShardTreeCertificate {
    public static final long CBOR_TAG = 39003;
    private static final int VERSION = 1;

    private final ShardId shard;
    private final List<byte[]> siblingHashList;

    ShardTreeCertificate(ShardId shard, List<byte[]> siblingHashList) {
        Objects.requireNonNull(shard, "Shard cannot be null");
        Objects.requireNonNull(siblingHashList, "Sibling hash list cannot be null");

        this.shard = shard;
        this.siblingHashList = siblingHashList.stream()
                .map(hash -> Arrays.copyOf(hash, hash.length))
                .collect(Collectors.toList());
    }

    public int getVersion() {
        return ShardTreeCertificate.VERSION;
    }

    /**
     * Get shard.
     *
     * @return shard
     */
    public ShardId getShard() {
        return this.shard;
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
     * Create shard tree certificate from CBOR bytes.
     *
     * @param bytes CBOR bytes
     * @return shard tree certificate
     */
    public static ShardTreeCertificate fromCbor(byte[] bytes) {
        CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
        if (tag.getTag() != ShardTreeCertificate.CBOR_TAG) {
            throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
        }
        List<byte[]> data = CborDeserializer.decodeArray(tag.getData());

        int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
        if (version != ShardTreeCertificate.VERSION) {
            throw new CborSerializationException(String.format("Unsupported version: %s", version));
        }

        return new ShardTreeCertificate(
                ShardId.decode(CborDeserializer.decodeByteString(data.get(1))),
                CborDeserializer.decodeArray(data.get(2)).stream()
                        .map(CborDeserializer::decodeByteString)
                        .collect(Collectors.toList())
        );
    }

    /**
     * Convert shard tree certificate to CBOR bytes.
     *
     * @return CBOR bytes
     */
    public byte[] toCbor() {
        return CborSerializer.encodeTag(
                ShardTreeCertificate.CBOR_TAG,
                CborSerializer.encodeArray(
                        CborSerializer.encodeUnsignedInteger(ShardTreeCertificate.VERSION),
                        CborSerializer.encodeByteString(this.shard.encode()),
                        CborSerializer.encodeArray(
                                this.siblingHashList.stream()
                                        .map(CborSerializer::encodeByteString)
                                        .toArray(byte[][]::new)
                        )
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
        return Objects.hash(ShardTreeCertificate.VERSION, this.shard, this.siblingHashList);
    }

    @Override
    public String toString() {
        return String.format("ShardTreeCertificate{shard=%s, siblingHashList=%s}",
                this.shard, this.siblingHashList);
    }
}
