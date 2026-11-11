package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.UnsignedLongs;

/**
 * The envelope of a GetTrustBase response (and of a release bundle, which is the same object):
 * {@code [1, network, status, fromEpoch, effectiveEnd, endEntryId|null, [entry...],
 * nextEpoch|null]} under tag 39015, the entries embedded verbatim. Only the envelope is checked
 * here; each user of the response applies its own rules to the contents.
 */
public final class TrustBaseResponseWire {

  /** CBOR tag of the response. */
  public static final long RESPONSE_TAG = 39015L;
  /** Version of the response. */
  public static final long RESPONSE_VERSION = 1L;
  /** Number of defined response statuses. */
  public static final long STATUS_COUNT = 8L;
  /** Status of a response that carries entries. */
  public static final long STATUS_OK = 0L;

  private static final int FIELD_COUNT = 8;

  private TrustBaseResponseWire() {
  }

  /** The fields of a response. Epochs, rounds and the status are unsigned bits. */
  public static final class Fields {

    private final long effectiveEnd;
    private final byte[] endEntryId;
    private final List<byte[]> entries;
    private final long fromEpoch;
    private final int network;
    private final Long nextEpoch;
    private final long status;

    /**
     * Create the fields.
     *
     * @param effectiveEnd last epoch of the range, unsigned
     * @param endEntryId id of the last entry, or null (copied)
     * @param entries complete tagged entries (copied)
     * @param fromEpoch first epoch of the range, unsigned
     * @param network network id, 0 to 65535
     * @param nextEpoch continuation cursor, or null
     * @param status response status, 0 to 7
     */
    public Fields(long effectiveEnd, byte[] endEntryId, List<byte[]> entries, long fromEpoch,
        int network, Long nextEpoch, long status) {
      this.effectiveEnd = effectiveEnd;
      this.endEntryId = endEntryId == null ? null : Arrays.copyOf(endEntryId, endEntryId.length);
      List<byte[]> copy = new ArrayList<>(entries.size());
      for (byte[] entry : entries) {
        copy.add(Arrays.copyOf(entry, entry.length));
      }
      this.entries = copy;
      this.fromEpoch = fromEpoch;
      this.network = network;
      this.nextEpoch = nextEpoch;
      this.status = status;
    }

    /** Get the last epoch of the range, unsigned bits. */
    public long getEffectiveEnd() {
      return this.effectiveEnd;
    }

    /** Get the end entry id, or null (copy). */
    public byte[] getEndEntryId() {
      return this.endEntryId == null ? null
          : Arrays.copyOf(this.endEntryId, this.endEntryId.length);
    }

    /** Get the embedded entries (copies). */
    public List<byte[]> getEntries() {
      List<byte[]> copy = new ArrayList<>(this.entries.size());
      for (byte[] entry : this.entries) {
        copy.add(Arrays.copyOf(entry, entry.length));
      }
      return copy;
    }

    /** Get the first epoch of the range, unsigned bits. */
    public long getFromEpoch() {
      return this.fromEpoch;
    }

    /** Get the network id of the header. */
    public int getNetwork() {
      return this.network;
    }

    /** Get the continuation cursor, or null. */
    public Long getNextEpoch() {
      return this.nextEpoch;
    }

    /** Get the status, unsigned bits. */
    public long getStatus() {
      return this.status;
    }
  }

  /**
   * Parse the envelope of a tagged response.
   *
   * @param bytes tagged response
   * @param maxEntries most entries accepted, checked from the array header before any entry is read
   * @return the fields
   * @throws TrustBaseEncodingException if the bytes are not the deterministic envelope or exceed
   *     the entry budget
   */
  public static Fields parse(byte[] bytes, int maxEntries) {
    Objects.requireNonNull(bytes, "bytes cannot be null");
    try {
      CborDeserializer.CborTag tagged = CborDeserializer.decodeTag(bytes);
      if (tagged.getTag() != RESPONSE_TAG) {
        throw new TrustBaseEncodingException(
            "Tag " + Long.toUnsignedString(tagged.getTag()) + ", expected " + RESPONSE_TAG + ".");
      }

      List<byte[]> fields = CborDeserializer.decodeArrayAtMost(tagged.getData(), FIELD_COUNT);
      if (fields.size() != FIELD_COUNT) {
        throw new TrustBaseEncodingException(
            "Expected " + FIELD_COUNT + " fields, got " + fields.size() + ".");
      }

      long version = uint(fields.get(0));
      if (version != RESPONSE_VERSION) {
        throw new TrustBaseEncodingException(
            "Version " + Long.toUnsignedString(version) + ", expected " + RESPONSE_VERSION + ".");
      }
      long network = uint(fields.get(1));
      // A header network of 0 is not refused here: no entry has network 0, so the entries cannot
      // match it and the response is refused as such by its user.
      if (UnsignedLongs.compare(network, 0xFFFFL) > 0) {
        throw new TrustBaseEncodingException(
            "Network identifier " + Long.toUnsignedString(network) + " above 65535.");
      }
      long status = uint(fields.get(2));
      if (UnsignedLongs.compare(status, STATUS_COUNT) >= 0) {
        throw new TrustBaseEncodingException(
            "Status " + Long.toUnsignedString(status) + " is not defined.");
      }
      byte[] endEntryId = CborDeserializer.decodeNullable(fields.get(5),
          CborDeserializer::decodeByteString);
      if (endEntryId != null && endEntryId.length != TrustBaseEntry.ID_LENGTH) {
        throw new TrustBaseEncodingException("End id of " + endEntryId.length + " bytes.");
      }
      List<byte[]> entries;
      try {
        entries = CborDeserializer.decodeArrayAtMost(fields.get(6), maxEntries);
      } catch (CborSerializationException e) {
        if (e.getMessage() != null && e.getMessage().startsWith("Array of more than")) {
          throw new TrustBaseEncodingException("More than " + maxEntries + " entries.");
        }
        throw e;
      }
      Long nextEpoch = CborDeserializer.decodeNullable(fields.get(7), TrustBaseResponseWire::uint);
      return new Fields(uint(fields.get(4)), endEntryId, entries, uint(fields.get(3)),
          (int) network, nextEpoch, status);
    } catch (TrustBaseEncodingException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TrustBaseEncodingException(String.valueOf(e.getMessage()));
    }
  }

  private static long uint(byte[] data) {
    return CborDeserializer.decodeUnsignedInteger(data).asLong();
  }

  /**
   * Encode a response, the entries embedded verbatim: the inverse of {@link #parse}.
   *
   * @param fields the fields; entries are complete tagged entries
   * @return tagged response
   */
  public static byte[] encode(Fields fields) {
    byte[][] entries = fields.entries.toArray(new byte[0][]);
    return CborSerializer.encodeTag(RESPONSE_TAG, CborSerializer.encodeArray(
        CborSerializer.encodeUnsignedInteger(RESPONSE_VERSION),
        CborSerializer.encodeUnsignedInteger(fields.network),
        CborSerializer.encodeUnsignedInteger(fields.status),
        CborSerializer.encodeUnsignedInteger(fields.fromEpoch),
        CborSerializer.encodeUnsignedInteger(fields.effectiveEnd),
        CborSerializer.encodeNullable(fields.endEntryId, CborSerializer::encodeByteString),
        CborSerializer.encodeArray(entries),
        CborSerializer.encodeNullable(fields.nextEpoch, CborSerializer::encodeUnsignedInteger)));
  }
}
