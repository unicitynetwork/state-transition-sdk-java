package org.unicitylabs.sdk.api.bft.trustbase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.Signature;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.UnsignedLongs;

/**
 * Signed Unicity Trust Base entry, record version 2 (CBOR tag 39000), the reference being
 * bft-go-base {@code types.RootTrustBaseV2}.
 *
 * <p>Wire form: tag 39000 over the 10-element array {@code [2, networkId, epoch, epochStartRound,
 * [[nodeId, sigKey, weight]...], quorumThreshold, stateHash|null, changeRecordHash|null,
 * previousEntryId, {nodeId: signature}|null]}.
 *
 * <p>Decoding is strict and has exactly one byte form per entry: at most {@link #MAX_ENTRY_BYTES}
 * bytes, exact tag, version and arity, at most {@link #MAX_MEMBERS} members and
 * {@link #MAX_ENTRY_SIGNATURES} signatures (checked from the array and map headers, before any
 * element is read), no trailing bytes, strict UTF-8, an absent hash is null (never an empty byte
 * string), and the input must equal the deterministic encoding of what was decoded. The identity
 * is {@code entryId = SHA-256(sigBytes)}, where sigBytes is the entry with the signature field
 * encoded as CBOR null, so two valid signature sets over the same entry give the same identity.
 * Epochs, rounds and weights are unsigned 64-bit numbers carried as {@code long} bit patterns.
 * This class checks structure; the signatures of a successor are checked by
 * {@link #verifySuccessor(TrustBaseEntry)} and the history that appends the entry.
 */
public final class TrustBaseEntry {

  /** The CBOR tag of an entry. */
  public static final long CBOR_TAG = 39000L;
  /** The record version. */
  public static final long VERSION = 2L;
  /** Length of an entry id and of the hashes when present. */
  public static final int ID_LENGTH = 32;
  /** Largest encoded entry (owner §49: 128 KiB). */
  public static final int MAX_ENTRY_BYTES = 128 * 1024;
  /** Most members of an entry. */
  public static final int MAX_MEMBERS = 256;
  /** Most signatures an entry carries or a quorum check is given, however small the entry is. */
  public static final int MAX_ENTRY_SIGNATURES = 256;
  /** Longest node id, in UTF-8 bytes. */
  public static final int MAX_NODE_ID_BYTES = 128;
  /** One member's weight bound, so that the sum over {@link #MAX_MEMBERS} cannot overflow. */
  public static final long MAX_WEIGHT = 1L << 40;
  /** Length of a compressed secp256k1 signing key. */
  public static final int SIGNING_KEY_LENGTH = 33;

  private static final int FIELD_COUNT = 10;
  private static final ECParameterSpec CURVE = ECNamedCurveTable.getParameterSpec("secp256k1");

  private final int network;
  private final long epoch;
  private final long epochStartRound;
  private final List<TrustBaseMember> members;
  private final Map<String, TrustBaseMember> memberIndex;
  private final long quorumThreshold;
  private final byte[] stateHash;
  private final byte[] changeRecordHash;
  private final byte[] previousEntryId;
  private final Map<String, byte[]> signatures;

  /**
   * Create an entry from its fields. Nothing is validated here: {@link #decode(byte[])} and
   * {@link #validate()} check the structure.
   *
   * @param network network id, 0 to 65535 (1 to 65535 in a valid entry)
   * @param epoch epoch, unsigned
   * @param epochStartRound first round of the epoch, unsigned
   * @param members the members, in node id byte order (copied)
   * @param quorumThreshold quorum threshold, unsigned
   * @param stateHash state hash or null (copied)
   * @param changeRecordHash change record hash or null (copied)
   * @param previousEntryId predecessor entry id, 32 zero bytes for a genesis (copied)
   * @param signatures predecessor members' signatures over {@link #sigBytes()} by node id, null
   *     for a genesis (copied)
   */
  public TrustBaseEntry(int network, long epoch, long epochStartRound,
      List<TrustBaseMember> members, long quorumThreshold, byte[] stateHash,
      byte[] changeRecordHash, byte[] previousEntryId, Map<String, byte[]> signatures) {
    this.network = network;
    this.epoch = epoch;
    this.epochStartRound = epochStartRound;
    this.members = List.copyOf(Objects.requireNonNull(members, "members cannot be null"));
    Map<String, TrustBaseMember> index = new HashMap<>();
    for (TrustBaseMember member : this.members) {
      index.putIfAbsent(member.getNodeId(), member);
    }
    this.memberIndex = index;
    this.quorumThreshold = quorumThreshold;
    this.stateHash = copy(stateHash);
    this.changeRecordHash = copy(changeRecordHash);
    this.previousEntryId = Arrays.copyOf(
        Objects.requireNonNull(previousEntryId, "previousEntryId cannot be null"),
        previousEntryId.length);
    this.signatures = signatures == null ? null : sortedCopy(signatures);
  }

  private static byte[] copy(byte[] bytes) {
    return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
  }

  private static Map<String, byte[]> sortedCopy(Map<String, byte[]> source) {
    List<Map.Entry<String, byte[]>> list = new ArrayList<>(source.entrySet());
    list.sort((a, b) -> compareBytes(utf8(a.getKey()), utf8(b.getKey())));
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> entry : list) {
      result.put(entry.getKey(), copy(entry.getValue()));
    }
    return Collections.unmodifiableMap(result);
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Compare byte arrays as unsigned bytes, then by length.
   *
   * @param a first
   * @param b second
   * @return negative, zero or positive
   */
  static int compareBytes(byte[] a, byte[] b) {
    int length = Math.min(a.length, b.length);
    for (int i = 0; i < length; i++) {
      int diff = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
      if (diff != 0) {
        return diff;
      }
    }
    return a.length - b.length;
  }

  /**
   * Get the network of the entry.
   *
   * @return network id (1 to 65535 on an entry that passed {@link #validate()})
   */
  public NetworkId getNetworkId() {
    return NetworkId.fromId((short) this.network);
  }

  /**
   * Get the epoch.
   *
   * @return epoch, unsigned bits
   */
  public long getEpoch() {
    return this.epoch;
  }

  /**
   * Get the first round of the epoch.
   *
   * @return start round, unsigned bits
   */
  public long getEpochStartRound() {
    return this.epochStartRound;
  }

  /**
   * Get the members, sorted by node id bytes.
   *
   * @return immutable list
   */
  public List<TrustBaseMember> getMembers() {
    return this.members;
  }

  /**
   * Find a member by node id.
   *
   * @param nodeId node id
   * @return the member, or null if the id names none
   */
  public TrustBaseMember findMember(String nodeId) {
    return this.memberIndex.get(nodeId);
  }

  /**
   * Get the quorum threshold.
   *
   * @return threshold, unsigned bits
   */
  public long getQuorumThreshold() {
    return this.quorumThreshold;
  }

  /**
   * Get the state hash.
   *
   * @return copy, or null when absent
   */
  public byte[] getStateHash() {
    return copy(this.stateHash);
  }

  /**
   * Get the change record hash.
   *
   * @return copy, or null when absent
   */
  public byte[] getChangeRecordHash() {
    return copy(this.changeRecordHash);
  }

  /**
   * Get the predecessor's entry id.
   *
   * @return copy; 32 zero bytes for the genesis entry
   */
  public byte[] getPreviousEntryId() {
    return copy(this.previousEntryId);
  }

  /**
   * Get the predecessor members' signatures over {@link #sigBytes()}.
   *
   * @return immutable map by node id in node id byte order (values are copies), null for the
   *     genesis entry
   */
  public Map<String, byte[]> getSignatures() {
    if (this.signatures == null) {
      return null;
    }
    return sortedCopy(this.signatures);
  }

  /**
   * Get the total voting weight of the members (valid entries only: it cannot overflow there).
   *
   * @return total weight
   */
  public long getTotalWeight() {
    long total = 0;
    for (TrustBaseMember member : this.members) {
      total += member.getWeight();
    }
    return total;
  }

  /**
   * Decode and structurally validate a tagged version-2 entry.
   *
   * @param bytes tagged entry
   * @return the entry
   * @throws TrustBaseEncodingException if the bytes are not the deterministic encoding of a
   *     version-2 entry
   * @throws TrustBaseInvalidException if a structure rule fails
   */
  public static TrustBaseEntry decode(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes cannot be null");
    if (bytes.length > MAX_ENTRY_BYTES) {
      throw new TrustBaseEncodingException("The entry exceeds the size bound.");
    }

    TrustBaseEntry entry;
    try {
      entry = parse(bytes);
    } catch (TrustBaseException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TrustBaseEncodingException(String.valueOf(e.getMessage()));
    }

    // One byte form per entry: the input must be what this decoder would write back.
    if (!Arrays.equals(entry.encode(), bytes)) {
      throw new TrustBaseEncodingException("Not the deterministic encoding.");
    }

    entry.validate();
    return entry;
  }

  private static TrustBaseEntry parse(byte[] bytes) {
    CborDeserializer.CborTag tagged = CborDeserializer.decodeTag(bytes);
    if (tagged.getTag() != CBOR_TAG) {
      throw new TrustBaseEncodingException(
          "Tag " + Long.toUnsignedString(tagged.getTag()) + ", expected " + CBOR_TAG + ".");
    }

    List<byte[]> fields = CborDeserializer.decodeArrayAtMost(tagged.getData(), FIELD_COUNT);
    if (fields.size() != FIELD_COUNT) {
      throw new TrustBaseEncodingException(
          "Expected " + FIELD_COUNT + " fields, got " + fields.size() + ".");
    }

    long version = CborDeserializer.decodeUnsignedInteger(fields.get(0)).asLong();
    if (version != VERSION) {
      throw new TrustBaseEncodingException(
          "Version " + Long.toUnsignedString(version) + ", expected " + VERSION + ".");
    }

    long network = CborDeserializer.decodeUnsignedInteger(fields.get(1)).asLong();
    // 0 fits the wire type and is a structure error (validate), as in the Go and Rust references;
    // only a value above 16 bits is a bad encoding.
    if (UnsignedLongs.compare(network, 0xFFFFL) > 0) {
      throw new TrustBaseEncodingException(
          "Network identifier " + Long.toUnsignedString(network) + " above 65535.");
    }

    List<byte[]> memberItems;
    try {
      memberItems = CborDeserializer.decodeArrayAtMost(fields.get(4), MAX_MEMBERS);
    } catch (CborSerializationException e) {
      throw new TrustBaseEncodingException("Too many members or a malformed member list.");
    }
    List<TrustBaseMember> members = new ArrayList<>(memberItems.size());
    for (byte[] item : memberItems) {
      List<byte[]> member = CborDeserializer.decodeArray(item, 3);
      members.add(new TrustBaseMember(
          CborDeserializer.decodeTextString(member.get(0)),
          CborDeserializer.decodeByteString(member.get(1)),
          CborDeserializer.decodeUnsignedInteger(member.get(2)).asLong()));
    }

    Map<String, byte[]> signatures = null;
    Set<CborSerializer.CborMap.Entry> pairs = CborDeserializer.decodeNullable(fields.get(9),
        item -> CborDeserializer.decodeMapAtMost(item, MAX_ENTRY_SIGNATURES));
    if (pairs != null) {
      signatures = new HashMap<>();
      for (CborSerializer.CborMap.Entry pair : pairs) {
        signatures.put(CborDeserializer.decodeTextString(pair.getKey()),
            CborDeserializer.decodeByteString(pair.getValue()));
      }
    }

    return new TrustBaseEntry(
        (int) network,
        CborDeserializer.decodeUnsignedInteger(fields.get(2)).asLong(),
        CborDeserializer.decodeUnsignedInteger(fields.get(3)).asLong(),
        members,
        CborDeserializer.decodeUnsignedInteger(fields.get(5)).asLong(),
        hash(fields.get(6)),
        hash(fields.get(7)),
        CborDeserializer.decodeByteString(fields.get(8)),
        signatures);
  }

  // An absent hash is null, never an empty byte string: that would be a second byte form (and a
  // second entry id) of the same entry.
  private static byte[] hash(byte[] data) {
    return CborDeserializer.decodeNullable(data, item -> {
      byte[] value = CborDeserializer.decodeByteString(item);
      if (value.length == 0) {
        throw new TrustBaseEncodingException("An absent hash is null, not an empty byte string.");
      }
      return value;
    });
  }

  /**
   * Get the tagged deterministic encoding, signatures included.
   *
   * @return bytes
   */
  public byte[] encode() {
    return this.encodeWithSignatures(this.signatures);
  }

  /**
   * Get the bytes the predecessor's members sign: the entry with the signature field encoded as
   * CBOR null.
   *
   * @return bytes
   */
  public byte[] sigBytes() {
    return this.encodeWithSignatures(null);
  }

  /**
   * Get the entry id, SHA-256 of {@link #sigBytes()}: independent of the signatures, what a
   * successor links to and a pin names.
   *
   * @return 32 bytes
   */
  public byte[] entryId() {
    return new DataHasher(HashAlgorithm.SHA256).update(this.sigBytes()).digest().getData();
  }

  /**
   * Tell whether the entry has no predecessor (32 zero bytes).
   *
   * @return true for a genesis entry
   */
  public boolean isGenesis() {
    for (byte b : this.previousEntryId) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }

  private byte[] encodeWithSignatures(Map<String, byte[]> withSignatures) {
    byte[][] memberItems = new byte[this.members.size()][];
    for (int i = 0; i < memberItems.length; i++) {
      TrustBaseMember member = this.members.get(i);
      memberItems[i] = CborSerializer.encodeArray(
          CborSerializer.encodeTextString(member.getNodeId()),
          CborSerializer.encodeByteString(member.getSigKey()),
          CborSerializer.encodeUnsignedInteger(member.getWeight()));
    }

    byte[] signatureField = CborSerializer.encodeNull();
    if (withSignatures != null) {
      Set<CborSerializer.CborMap.Entry> pairs = new HashSet<>();
      for (Map.Entry<String, byte[]> signature : withSignatures.entrySet()) {
        pairs.add(new CborSerializer.CborMap.Entry(
            CborSerializer.encodeTextString(signature.getKey()),
            CborSerializer.encodeByteString(signature.getValue())));
      }
      signatureField = CborSerializer.encodeMap(new CborSerializer.CborMap(pairs));
    }

    return CborSerializer.encodeTag(CBOR_TAG, CborSerializer.encodeArray(
        CborSerializer.encodeUnsignedInteger(VERSION),
        CborSerializer.encodeUnsignedInteger(this.network),
        CborSerializer.encodeUnsignedInteger(this.epoch),
        CborSerializer.encodeUnsignedInteger(this.epochStartRound),
        CborSerializer.encodeArray(memberItems),
        CborSerializer.encodeUnsignedInteger(this.quorumThreshold),
        CborSerializer.encodeNullable(this.stateHash, CborSerializer::encodeByteString),
        CborSerializer.encodeNullable(this.changeRecordHash, CborSerializer::encodeByteString),
        CborSerializer.encodeByteString(this.previousEntryId),
        signatureField));
  }

  /**
   * Check the entry's structure, not its signatures: network and epoch at least 1, the member list
   * (sorted by node id bytes, unique, valid compressed keys, positive bounded weights, bounded
   * count), the exact quorum threshold, hash lengths, and the genesis (zero predecessor, null
   * signatures) versus non-genesis (32-byte predecessor, non-empty signature map of at most
   * {@link #MAX_ENTRY_SIGNATURES}) shape.
   *
   * @throws TrustBaseInvalidException if a rule fails
   */
  public void validate() {
    if (this.network <= 0 || this.network > 0xFFFF) {
      throw new TrustBaseInvalidException("Network identifier is zero or above 65535.");
    }
    if (this.epoch == 0) {
      throw new TrustBaseInvalidException("Epoch is zero.");
    }

    int count = this.members.size();
    if (count == 0 || count > MAX_MEMBERS) {
      throw new TrustBaseInvalidException(count + " members (1.." + MAX_MEMBERS + ").");
    }

    long total = 0;
    Map<String, String> keys = new HashMap<>();
    byte[] previousId = null;
    for (TrustBaseMember member : this.members) {
      byte[] id = utf8(member.getNodeId());
      if (id.length == 0 || id.length > MAX_NODE_ID_BYTES) {
        throw new TrustBaseInvalidException(
            "Node id of " + id.length + " bytes (1.." + MAX_NODE_ID_BYTES + ").");
      }
      if (previousId != null && compareBytes(previousId, id) >= 0) {
        throw new TrustBaseInvalidException(
            "Members are not strictly sorted by node id at \"" + member.getNodeId() + "\".");
      }
      previousId = id;

      long weight = member.getWeight();
      if (weight == 0 || UnsignedLongs.compare(weight, MAX_WEIGHT) > 0) {
        throw new TrustBaseInvalidException("Member \"" + member.getNodeId() + "\": weight "
            + Long.toUnsignedString(weight) + " outside 1.." + MAX_WEIGHT + ".");
      }

      byte[] key = member.getSigKey();
      if (!isCompressedSecp256k1Key(key)) {
        throw new TrustBaseInvalidException(
            "Member \"" + member.getNodeId() + "\": not a compressed secp256k1 key.");
      }
      // A compressed key has one encoding per point, so a byte comparison finds a repeated key:
      // one key holder must not count for several members.
      String other = keys.put(HexConverter.encode(key), member.getNodeId());
      if (other != null) {
        throw new TrustBaseInvalidException(
            "Members \"" + other + "\" and \"" + member.getNodeId() + "\" share a signing key.");
      }

      total += weight;
    }

    long threshold = (total * 2) / 3 + 1;
    if (this.quorumThreshold != threshold) {
      throw new TrustBaseInvalidException("Quorum threshold "
          + Long.toUnsignedString(this.quorumThreshold) + ", expected " + threshold
          + " for total weight " + total + ".");
    }

    if (this.stateHash != null && this.stateHash.length != ID_LENGTH) {
      throw new TrustBaseInvalidException(
          "stateHash is " + this.stateHash.length + " bytes (null or " + ID_LENGTH + ").");
    }
    if (this.changeRecordHash != null && this.changeRecordHash.length != ID_LENGTH) {
      throw new TrustBaseInvalidException("changeRecordHash is " + this.changeRecordHash.length
          + " bytes (null or " + ID_LENGTH + ").");
    }

    if (this.previousEntryId.length != ID_LENGTH) {
      throw new TrustBaseInvalidException(
          "Previous entry id is " + this.previousEntryId.length + " bytes.");
    }
    if (this.isGenesis()) {
      if (this.signatures != null) {
        throw new TrustBaseInvalidException("The genesis entry carries signatures.");
      }
    } else if (this.signatures == null || this.signatures.isEmpty()) {
      throw new TrustBaseInvalidException("A non-genesis entry has no signatures.");
    } else if (this.signatures.size() > MAX_ENTRY_SIGNATURES) {
      throw new TrustBaseInvalidException(
          this.signatures.size() + " signatures (at most " + MAX_ENTRY_SIGNATURES + ").");
    }
  }

  /**
   * Check that this entry is a structurally valid genesis entry (zero predecessor, null
   * signatures). A genesis is authenticated by a pin on its entry id, never by signatures; the
   * history compares the pin.
   *
   * @throws TrustBaseInvalidException if the entry is not a genesis entry
   */
  public void verifyGenesis() {
    this.validate();
    if (!this.isGenesis()) {
      throw new TrustBaseInvalidException("Not a genesis entry.");
    }
  }

  /**
   * Check the signatures of this entry's members over {@code data}, strictly: at most
   * {@link #MAX_ENTRY_SIGNATURES} are supplied, every supplied signature must be from a member and
   * valid (a supplied extra that is unknown or invalid fails the whole check even if the rest reach
   * quorum), and the weight of the signers must reach the threshold.
   *
   * @param data signed bytes
   * @param signaturesByNodeId signatures by node id
   * @throws TrustBaseSignatureException if a signature fails or the quorum is not reached
   */
  public void verifyQuorumSignatures(byte[] data, Map<String, byte[]> signaturesByNodeId) {
    Objects.requireNonNull(signaturesByNodeId, "signatures cannot be null");
    if (signaturesByNodeId.size() > MAX_ENTRY_SIGNATURES) {
      throw new TrustBaseSignatureException("Too many signatures.");
    }

    long signed = 0;
    for (Map.Entry<String, byte[]> signature : signaturesByNodeId.entrySet()) {
      signed += this.verifySignature(data, signature.getValue(), signature.getKey());
    }
    if (UnsignedLongs.compare(signed, this.quorumThreshold) < 0) {
      throw new TrustBaseSignatureException("Quorum not reached, signed weight " + signed
          + ", threshold " + Long.toUnsignedString(this.quorumThreshold) + ".");
    }
  }

  /**
   * Verify one member's signature and return the member's weight. The signature must be a
   * canonical recoverable signature over SHA-256 of {@code data}: exactly 65 bytes
   * ({@code r || s || recovery}), recovery id 0..3, low-S, recovering to the member's key. The
   * 64-byte compact form is not accepted. This is the rule of the seal verifier, applied to
   * entries.
   *
   * @param data signed bytes
   * @param signature 65-byte signature
   * @param nodeId signer
   * @return the signer's weight
   * @throws TrustBaseSignatureException if the signer is not a member or the signature is not valid
   */
  public long verifySignature(byte[] data, byte[] signature, String nodeId) {
    TrustBaseMember member = this.memberIndex.get(nodeId);
    if (member == null) {
      throw new TrustBaseSignatureException("Signer \"" + nodeId + "\" is not a member.");
    }

    Signature decoded;
    try {
      decoded = Signature.decode(signature);
    } catch (IllegalArgumentException e) {
      throw new TrustBaseSignatureException("Signer \"" + nodeId + "\": " + e.getMessage());
    }

    DataHash hash = new DataHasher(HashAlgorithm.SHA256).update(data).digest();
    if (!SigningService.verifyWithPublicKey(hash, decoded, member.getSigKey())) {
      throw new TrustBaseSignatureException(
          "Signer \"" + nodeId + "\": the signature is not valid.");
    }

    return member.getWeight();
  }

  /**
   * Check that this entry extends {@code previous}: not a genesis, same network, epoch previous +
   * 1, a later start round, the predecessor id equal to the entry id of {@code previous}, and a
   * strict quorum of {@code previous}' members signing {@link #sigBytes()}.
   *
   * @param previous the predecessor
   * @throws TrustBaseChainException if the entry does not extend the predecessor
   * @throws TrustBaseSignatureException if the predecessor's members did not sign it
   */
  public void verifySuccessor(TrustBaseEntry previous) {
    Objects.requireNonNull(previous, "previous cannot be null");
    if (this.isGenesis()) {
      throw new TrustBaseChainException("A genesis entry has no predecessor.");
    }
    if (this.network != previous.network) {
      throw new TrustBaseChainException(
          "Network " + this.network + ", predecessor " + previous.network + ".");
    }
    if (this.epoch != previous.epoch + 1 || UnsignedLongs.compare(previous.epoch, -1L) == 0) {
      throw new TrustBaseChainException("Epoch " + Long.toUnsignedString(this.epoch)
          + ", predecessor " + Long.toUnsignedString(previous.epoch) + ".");
    }
    if (UnsignedLongs.compare(this.epochStartRound, previous.epochStartRound) <= 0) {
      throw new TrustBaseChainException("Epoch start " + Long.toUnsignedString(this.epochStartRound)
          + ", predecessor " + Long.toUnsignedString(previous.epochStartRound) + ".");
    }
    if (!Arrays.equals(this.previousEntryId, previous.entryId())) {
      throw new TrustBaseChainException("Previous entry id does not match.");
    }

    // A decoded non-genesis entry always carries a non-empty signature map (validate).
    previous.verifyQuorumSignatures(this.sigBytes(),
        this.signatures == null ? Map.of() : this.signatures);
  }

  private static boolean isCompressedSecp256k1Key(byte[] key) {
    if (key.length != SIGNING_KEY_LENGTH || (key[0] != 0x02 && key[0] != 0x03)) {
      return false;
    }
    try {
      return !CURVE.getCurve().decodePoint(key).isInfinity();
    } catch (IllegalArgumentException | ArithmeticException e) {
      return false;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof TrustBaseEntry && Arrays.equals(this.encode(), ((TrustBaseEntry) o).encode());
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(this.encode());
  }
}
