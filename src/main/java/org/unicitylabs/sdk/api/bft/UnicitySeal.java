package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.crypto.secp256k1.Signature;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer.CborTag;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.UnsignedLongs;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * UnicitySeal represents a seal in the Unicity BFT system, containing metadata and signatures.
 */
public class UnicitySeal {
  public static final long CBOR_TAG = 39005;
  private static final int VERSION = 1;

  private final NetworkId networkId;
  private final long rootChainRoundNumber;
  private final long epoch;
  private final long timestamp;
  private final byte[] previousHash; // nullable
  private final byte[] hash;
  // signer id to the witness exactly as received; null when the seal carries no signature map. A
  // witness that is not a valid 65-byte signature is kept (it is part of every memo key and of the
  // re-encoding) but never counts as a vote.
  private final Map<String, byte[]> witnesses;

  UnicitySeal(
          NetworkId networkId,
          long rootChainRoundNumber,
          long epoch,
          long timestamp,
          byte[] previousHash,
          byte[] hash,
          Set<SignatureEntry> signatures
  ) {
    this(networkId, rootChainRoundNumber, epoch, timestamp, previousHash, hash,
            toWitnesses(signatures));
  }

  private UnicitySeal(
          NetworkId networkId,
          long rootChainRoundNumber,
          long epoch,
          long timestamp,
          byte[] previousHash,
          byte[] hash,
          Map<String, byte[]> witnesses
  ) {
    Objects.requireNonNull(hash, "Hash cannot be null");

    this.networkId = networkId;
    this.rootChainRoundNumber = rootChainRoundNumber;
    this.epoch = epoch;
    this.timestamp = timestamp;
    this.previousHash = previousHash != null ? Arrays.copyOf(previousHash, previousHash.length)
            : null;
    this.hash = Arrays.copyOf(hash, hash.length);
    if (witnesses == null) {
      this.witnesses = null;
    } else {
      Map<String, byte[]> copy = new LinkedHashMap<>();
      for (Map.Entry<String, byte[]> entry : witnesses.entrySet()) {
        copy.put(
                Objects.requireNonNull(entry.getKey(), "Signer id cannot be null"),
                Arrays.copyOf(entry.getValue(), entry.getValue().length)
        );
      }
      this.witnesses = copy;
    }
  }

  private static Map<String, byte[]> toWitnesses(Set<SignatureEntry> signatures) {
    if (signatures == null) {
      return null;
    }
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (SignatureEntry entry : signatures) {
      Objects.requireNonNull(entry, "Signature entry cannot be null");
      Objects.requireNonNull(entry.getSignature(), "Signature cannot be null");
      result.put(entry.getKey(), entry.getSignature().encode());
    }
    return result;
  }

  /**
   * Create a new UnicitySeal instance with the provided signatures.
   *
   * @param signatures the signatures to include in the new UnicitySeal
   * @return a new UnicitySeal instance with the specified signatures
   */
  public UnicitySeal withSignatures(Set<SignatureEntry> signatures) {
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
  public NetworkId getNetworkId() {
    return this.networkId;
  }

  /**
   * Get the root chain round number as unsigned 64-bit bits: a value of 2^63 or above reads as a
   * negative {@code long}. See {@link #getRootChainRoundNumberUnsigned()}.
   *
   * @return root chain round number bits
   */
  public long getRootChainRoundNumber() {
    return this.rootChainRoundNumber;
  }

  /**
   * Get the root chain round number as a non-negative integer.
   *
   * @return root chain round number
   */
  public BigInteger getRootChainRoundNumberUnsigned() {
    return UnsignedLongs.toBigInteger(this.rootChainRoundNumber);
  }

  /**
   * Get the root chain round number in unsigned decimal.
   *
   * @return root chain round number
   */
  public String getRootChainRoundNumberDecimal() {
    return UnsignedLongs.toDecimal(this.rootChainRoundNumber);
  }

  /**
   * Get the epoch as unsigned 64-bit bits. See {@link #getEpochUnsigned()}.
   *
   * @return epoch bits
   */
  public long getEpoch() {
    return this.epoch;
  }

  /**
   * Get the epoch as a non-negative integer.
   *
   * @return epoch
   */
  public BigInteger getEpochUnsigned() {
    return UnsignedLongs.toBigInteger(this.epoch);
  }

  /**
   * Get the epoch in unsigned decimal.
   *
   * @return epoch
   */
  public String getEpochDecimal() {
    return UnsignedLongs.toDecimal(this.epoch);
  }

  /**
   * Get the timestamp (Unix seconds) as unsigned 64-bit bits. See {@link #getTimestampUnsigned()}.
   *
   * @return timestamp bits
   */
  public long getTimestamp() {
    return this.timestamp;
  }

  /**
   * Get the timestamp as a non-negative integer.
   *
   * @return timestamp
   */
  public BigInteger getTimestampUnsigned() {
    return UnsignedLongs.toBigInteger(this.timestamp);
  }

  /**
   * Get the timestamp in unsigned decimal.
   *
   * @return timestamp
   */
  public String getTimestampDecimal() {
    return UnsignedLongs.toDecimal(this.timestamp);
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
   * Get the well-formed signatures (65 bytes, recovery id 0..3). A carried witness that is not
   * well-formed is not listed here: it stays in the seal, is re-encoded and hashed unchanged, and
   * never counts as a vote. See {@link #getWitnesses()} for every witness as received.
   *
   * @return signatures, or null if the seal carries no signature map
   */
  public Set<SignatureEntry> getSignatures() {
    if (this.witnesses == null) {
      return null;
    }
    Set<SignatureEntry> result = new LinkedHashSet<>();
    for (Map.Entry<String, byte[]> entry : this.witnesses.entrySet()) {
      try {
        result.add(new SignatureEntry(entry.getKey(), Signature.decode(entry.getValue())));
      } catch (IllegalArgumentException e) {
        // malformed witness: carried, never counted
      }
    }
    return Set.copyOf(result);
  }

  /**
   * Get every signature witness exactly as received, the byte string of each map entry, well-formed
   * or not. The quorum rule decodes each one strictly and counts a malformed one as zero votes, as
   * the Go and Rust clients do.
   *
   * @return signer id to witness copies in encoding order, or null if the seal carries no
   *     signature map
   */
  public Map<String, byte[]> getWitnesses() {
    if (this.witnesses == null) {
      return null;
    }
    Map<String, byte[]> copy = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> entry : this.witnesses.entrySet()) {
      copy.put(entry.getKey(), Arrays.copyOf(entry.getValue(), entry.getValue().length));
    }
    return copy;
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
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), 8);

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != UnicitySeal.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    NetworkId networkId;
    try {
      networkId = NetworkId.fromId(CborDeserializer.decodeUnsignedInteger(data.get(1)).asShort());
    } catch (IllegalArgumentException e) {
      throw new CborSerializationException("Invalid network identifier.", e);
    }

    // a seal that arrives over the wire carries its signature map; the null form exists only for
    // the seal hash, which is computed over the seal without signatures
    Map<String, byte[]> witnesses = new LinkedHashMap<>();
    for (CborMap.Entry entry : CborDeserializer.decodeMap(data.get(7))) {
      String signer = CborDeserializer.decodeTextString(entry.getKey());
      if (witnesses.put(signer, CborDeserializer.decodeByteString(entry.getValue())) != null) {
        throw new CborSerializationException("Duplicate signer id in seal signatures.");
      }
    }

    return new UnicitySeal(
            networkId,
            CborDeserializer.decodeUnsignedInteger(data.get(2)).asLong(),
            CborDeserializer.decodeUnsignedInteger(data.get(3)).asLong(),
            CborDeserializer.decodeUnsignedInteger(data.get(4)).asLong(),
            CborDeserializer.decodeNullable(data.get(5), CborDeserializer::decodeByteString),
            CborDeserializer.decodeByteString(data.get(6)),
            witnesses
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
                    CborSerializer.encodeUnsignedInteger(this.networkId.getId()),
                    CborSerializer.encodeUnsignedInteger(this.rootChainRoundNumber),
                    CborSerializer.encodeUnsignedInteger(this.epoch),
                    CborSerializer.encodeUnsignedInteger(this.timestamp),
                    CborSerializer.encodeNullable(this.previousHash, CborSerializer::encodeByteString),
                    CborSerializer.encodeByteString(this.hash),
                    CborSerializer.encodeNullable(
                            this.witnesses,
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
            (Map<String, byte[]>) null
    ).toCbor();
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof UnicitySeal)) {
      return false;
    }
    UnicitySeal that = (UnicitySeal) o;
    return Objects.equals(this.networkId, that.networkId)
            && Objects.equals(this.rootChainRoundNumber, that.rootChainRoundNumber)
            && Objects.equals(this.epoch, that.epoch)
            && Objects.equals(this.timestamp, that.timestamp)
            && Objects.deepEquals(this.previousHash, that.previousHash)
            && Objects.deepEquals(this.hash, that.hash)
            && witnessesEqual(this.witnesses, that.witnesses);
  }

  private static boolean witnessesEqual(Map<String, byte[]> a, Map<String, byte[]> b) {
    if (a == null || b == null) {
      return a == b;
    }
    if (!a.keySet().equals(b.keySet())) {
      return false;
    }
    for (Map.Entry<String, byte[]> entry : a.entrySet()) {
      if (!Arrays.equals(entry.getValue(), b.get(entry.getKey()))) {
        return false;
      }
    }
    return true;
  }

  private static int witnessesHash(Map<String, byte[]> witnesses) {
    if (witnesses == null) {
      return 0;
    }
    int result = 1;
    for (Map.Entry<String, byte[]> entry : witnesses.entrySet()) {
      result += entry.getKey().hashCode() ^ Arrays.hashCode(entry.getValue());
    }
    return result;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.networkId, this.rootChainRoundNumber, this.epoch,
            this.timestamp,
            Arrays.hashCode(this.previousHash), Arrays.hashCode(this.hash),
            witnessesHash(this.witnesses));
  }

  @Override
  public String toString() {
    return String.format(
            "UnicitySeal{networkId=%s, rootChainRoundNumber=%s, epoch=%s, timestamp=%s, "
                    + "previousHash=%s, hash=%s, signers=%s}",
            this.networkId,
            this.getRootChainRoundNumberDecimal(),
            this.getEpochDecimal(),
            this.getTimestampDecimal(),
            this.previousHash != null ? HexConverter.encode(this.previousHash) : null,
            HexConverter.encode(this.hash),
            this.witnesses != null ? this.witnesses.keySet() : null
    );
  }

  public static final class SignatureEntry {
    private final String key;
    private final Signature signature;

    SignatureEntry(String key, Signature signature) {
      this.key = key;
      this.signature = signature;
    }

    public String getKey() {
      return this.key;
    }

    public Signature getSignature() {
      return this.signature;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof SignatureEntry)) return false;
      SignatureEntry that = (SignatureEntry) o;
      return Objects.equals(this.key, that.key);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.key);
    }

    @Override
    public String toString() {
      return String.format("SignatureEntry{key=%s, signature=%s}", this.key, this.signature);
    }
  }
}
