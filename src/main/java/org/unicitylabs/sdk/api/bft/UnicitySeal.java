package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer.CborTag;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * UnicitySeal represents a seal in the Unicity BFT system, containing metadata and signatures.
 */
public class UnicitySeal {
  public static final long CBOR_TAG = 39005;
  private static final int VERSION = 1;

  private final short networkId;
  private final long rootChainRoundNumber;
  private final long epoch;
  private final long timestamp;
  private final byte[] previousHash; // nullable
  private final byte[] hash;
  private final LinkedHashMap<String, byte[]> signatures;

  UnicitySeal(
          short networkId,
          long rootChainRoundNumber,
          long epoch,
          long timestamp,
          byte[] previousHash,
          byte[] hash,
          Map<String, byte[]> signatures
  ) {
    Objects.requireNonNull(hash, "Hash cannot be null");

    this.networkId = networkId;
    this.rootChainRoundNumber = rootChainRoundNumber;
    this.epoch = epoch;
    this.timestamp = timestamp;
    this.previousHash = previousHash;
    this.hash = hash;
    this.signatures = signatures == null
            ? null
            : signatures.entrySet().stream()
              .map(entry -> Map.entry(
                              entry.getKey(),
                              Arrays.copyOf(entry.getValue(), entry.getValue().length)
                      )
              )
              .collect(
                      Collectors.toMap(
                              Map.Entry::getKey,
                              Map.Entry::getValue,
                              (e1, e2) -> e1,
                              LinkedHashMap::new
                      )
              );
  }

  /**
   * Create a new UnicitySeal instance with the provided signatures.
   *
   * @param signatures the signatures to include in the new UnicitySeal
   * @return a new UnicitySeal instance with the specified signatures
   */
  public UnicitySeal withSignatures(Map<String, byte[]> signatures) {
    return new UnicitySeal(
            this.networkId,
            this.rootChainRoundNumber,
            this.epoch,
            this.timestamp,
            this.previousHash,
            this.hash,
            signatures
    );
  }

  public int getVersion() {
    return UnicitySeal.VERSION;
  }

  /**
   * Get the network ID.
   *
   * @return network ID
   */
  public short getNetworkId() {
    return this.networkId;
  }

  /**
   * Get the root chain round number.
   *
   * @return root chain round number
   */
  public long getRootChainRoundNumber() {
    return this.rootChainRoundNumber;
  }

  /**
   * Get the epoch.
   *
   * @return epoch
   */
  public long getEpoch() {
    return this.epoch;
  }

  /**
   * Get the timestamp.
   *
   * @return timestamp
   */
  public long getTimestamp() {
    return this.timestamp;
  }

  /**
   * Get the previous hash.
   *
   * @return previous hash or null if not set
   */
  public byte[] getPreviousHash() {
    return this.previousHash != null ? Arrays.copyOf(this.previousHash, this.previousHash.length)
            : null;
  }

  /**
   * Get the hash.
   *
   * @return hash
   */
  public byte[] getHash() {
    return Arrays.copyOf(this.hash, this.hash.length);
  }

  /**
   * Get the signatures.
   *
   * @return signatures
   */
  public Map<String, byte[]> getSignatures() {
    return this.signatures == null
            ? null
            : this.signatures.entrySet().stream()
              .map(entry -> Map.entry(
                              entry.getKey(),
                              Arrays.copyOf(entry.getValue(), entry.getValue().length)
                      )
              )
              .collect(
                      Collectors.toMap(
                              Map.Entry::getKey,
                              Map.Entry::getValue,
                              (e1, e2) -> e1,
                              LinkedHashMap::new
                      )
              );
  }

  /**
   * Deserialize unicity seal from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return unicity seal
   */
  public static UnicitySeal fromCbor(byte[] bytes) {
    CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != UnicitySeal.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData());

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != UnicitySeal.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return new UnicitySeal(
            CborDeserializer.decodeUnsignedInteger(data.get(1)).asShort(),
            CborDeserializer.decodeUnsignedInteger(data.get(2)).asLong(),
            CborDeserializer.decodeUnsignedInteger(data.get(3)).asLong(),
            CborDeserializer.decodeUnsignedInteger(data.get(4)).asLong(),
            CborDeserializer.decodeNullable(data.get(5), CborDeserializer::decodeByteString),
            CborDeserializer.decodeByteString(data.get(6)),
            CborDeserializer.decodeMap(data.get(7)).stream()
                    .collect(
                            Collectors.toMap(
                                    entry -> CborDeserializer.decodeTextString(entry.getKey()),
                                    entry -> CborDeserializer.decodeByteString(entry.getValue()
                                    )
                            )
                    )
    );
  }

  /**
   * Serialize unicity seal to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
            UnicitySeal.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(UnicitySeal.VERSION),
                    CborSerializer.encodeUnsignedInteger(this.networkId),
                    CborSerializer.encodeUnsignedInteger(this.rootChainRoundNumber),
                    CborSerializer.encodeUnsignedInteger(this.epoch),
                    CborSerializer.encodeUnsignedInteger(this.timestamp),
                    CborSerializer.encodeOptional(this.previousHash, CborSerializer::encodeByteString),
                    CborSerializer.encodeByteString(this.hash),
                    CborSerializer.encodeOptional(
                            this.signatures,
                            (signatures) -> CborSerializer.encodeMap(
                                    new CborMap(
                                            signatures.entrySet().stream()
                                                    .map(entry -> new CborMap.Entry(
                                                                    CborSerializer.encodeTextString(entry.getKey()),
                                                                    CborSerializer.encodeByteString(entry.getValue())
                                                            )
                                                    )
                                                    .collect(Collectors.toSet())
                                    )
                            )
                    )
            )
    );
  }

  /**
   * Convert unicity seal to CBOR bytes without signatures.
   *
   * @return CBOR bytes without signatures
   */
  public byte[] toCborWithoutSignatures() {
    return new UnicitySeal(
            this.networkId,
            this.rootChainRoundNumber,
            this.epoch,
            this.timestamp,
            this.previousHash,
            this.hash,
            null
    ).toCbor();
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof UnicitySeal)) {
      return false;
    }
    UnicitySeal that = (UnicitySeal) o;
    return Objects.equals(this.networkId,
            that.networkId) && Objects.equals(this.rootChainRoundNumber, that.rootChainRoundNumber)
            && Objects.equals(this.epoch, that.epoch) && Objects.equals(this.timestamp,
            that.timestamp) && Objects.deepEquals(this.previousHash, that.previousHash)
            && Objects.deepEquals(this.hash, that.hash) && Objects.equals(this.signatures,
            that.signatures);
  }

  @Override
  public int hashCode() {
    return Objects.hash(UnicitySeal.VERSION, this.networkId, this.rootChainRoundNumber, this.epoch,
            this.timestamp,
            Arrays.hashCode(this.previousHash), Arrays.hashCode(this.hash), this.signatures);
  }

  @Override
  public String toString() {
    return String.format(
            "UnicitySeal{networkId=%s, rootChainRoundNumber=%s, epoch=%s, timestamp=%s, "
                    + "previousHash=%s, hash=%s, signatures=%s",
            this.networkId,
            this.rootChainRoundNumber,
            this.epoch,
            this.timestamp,
            this.previousHash != null ? HexConverter.encode(this.previousHash) : null,
            HexConverter.encode(this.hash),
            this.signatures.entrySet()
                    .stream()
                    .map(entry -> String.format("%s: %s", entry.getKey(),
                            HexConverter.encode(entry.getValue())))
                    .collect(Collectors.toList())
    );
  }
}
