package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;

/**
 * One page of the GetTrustBase protocol: the tag-39015 response to a request for the entries from an epoch on.
 *
 * <p>A status other than OK carries no entries and no cursor. An OK page is a contiguous ascending
 * run of entries of the response's network starting at {@code fromEpoch}, linked by entry id, ending
 * at {@code effectiveEnd} when no cursor remains, or at the epoch before the cursor otherwise. The
 * reference is bft-core {@code gettrustbase.DecodeResponse}.
 *
 * <p>Decoding checks structure and links only. Nothing here is authenticated: {@code effectiveEnd}
 * and {@code endEntryId} are the source's claim, and every entry is authenticated by the history's
 * {@code append} from the latest held entry.
 */
public final class TrustBaseRange {

  /** CBOR tag of the GetTrustBase response (and of a release bundle, which is the same object). */
  public static final long RESPONSE_TAG = 39015L;
  /** Version of the GetTrustBase response. */
  public static final long RESPONSE_VERSION = 1L;

  private static final int FIELD_COUNT = 8;

  private final TrustBaseStatus status;
  private final NetworkId networkId;
  private final long fromEpoch;
  private final long effectiveEnd;
  private final byte[] endEntryId;
  private final List<TrustBaseEntry> entries;
  private final Long nextEpoch;

  private TrustBaseRange(TrustBaseStatus status, NetworkId networkId, long fromEpoch,
      long effectiveEnd, byte[] endEntryId, List<TrustBaseEntry> entries, Long nextEpoch) {
    this.status = status;
    this.networkId = networkId;
    this.fromEpoch = fromEpoch;
    this.effectiveEnd = effectiveEnd;
    this.endEntryId = endEntryId;
    this.entries = Collections.unmodifiableList(entries);
    this.nextEpoch = nextEpoch;
  }

  /**
   * Get the status.
   *
   * @return the status
   */
  public TrustBaseStatus getStatus() {
    return this.status;
  }

  /**
   * Get the network of the response (the one that was asked for).
   *
   * @return the network
   */
  public NetworkId getNetworkId() {
    return this.networkId;
  }

  /**
   * Get the first epoch of the page (advisory for a status other than OK).
   *
   * @return the epoch, an unsigned 64-bit value
   */
  public long getFromEpoch() {
    return this.fromEpoch;
  }

  /**
   * Get the source's last epoch (advisory for a status other than OK).
   *
   * @return the epoch, an unsigned 64-bit value
   */
  public long getEffectiveEnd() {
    return this.effectiveEnd;
  }

  /**
   * Get the identity of the entry at the effective end (absent or advisory for a status other than
   * OK; unchecked on a cursor page that ends before it).
   *
   * @return a copy of the entry id, or null
   */
  public byte[] getEndEntryId() {
    return this.endEntryId == null ? null : Arrays.copyOf(this.endEntryId, this.endEntryId.length);
  }

  /**
   * Get the entries, structurally decoded and linked, signatures unchecked. Empty unless the status
   * is OK.
   *
   * @return the entries
   */
  public List<TrustBaseEntry> getEntries() {
    return this.entries;
  }

  /**
   * Get the epoch to ask for next, when the page does not reach the effective end.
   *
   * @return the epoch (unsigned), or null
   */
  public Long getNextEpoch() {
    return this.nextEpoch;
  }

  /**
   * Strictly decode one page.
   *
   * @param bytes tagged response
   * @param limits size budget, checked before allocation
   * @param expectedNetwork the network that was asked for; a response for another one is refused
   * @return the page
   * @throws TrustBaseEncodingException if the bytes (or an embedded entry) are not the deterministic
   *     encoding, or exceed the byte budget
   * @throws TrustBaseResponseException if the page is inconsistent: network, status and cursor
   *     rules, entry count, start, order, links, end
   */
  public static TrustBaseRange decode(byte[] bytes, TrustBaseRangeLimits limits,
      NetworkId expectedNetwork) {
    Objects.requireNonNull(bytes, "bytes cannot be null");
    Objects.requireNonNull(limits, "limits cannot be null");
    Objects.requireNonNull(expectedNetwork, "expectedNetwork cannot be null");
    if (bytes.length > limits.getMaxBytes()) {
      throw new TrustBaseEncodingException("The response exceeds the byte budget.");
    }

    // The entry budget is a response rule, not an encoding rule: read one more than allowed.
    long version;
    long networkValue;
    long statusValue;
    long fromEpoch;
    long effectiveEnd;
    byte[] endEntryId;
    List<byte[]> items;
    Long nextEpoch;
    try {
      CborDeserializer.CborTag tagged = CborDeserializer.decodeTag(bytes);
      if (tagged.getTag() != RESPONSE_TAG) {
        throw new TrustBaseEncodingException("Not a GetTrustBase response (tag).");
      }
      List<byte[]> fields = CborDeserializer.decodeArrayAtMost(tagged.getData(), FIELD_COUNT);
      if (fields.size() != FIELD_COUNT) {
        throw new TrustBaseEncodingException("The response is not an 8-element array.");
      }
      version = CborDeserializer.decodeUnsignedInteger(fields.get(0)).asLong();
      networkValue = CborDeserializer.decodeUnsignedInteger(fields.get(1)).asLong();
      statusValue = CborDeserializer.decodeUnsignedInteger(fields.get(2)).asLong();
      fromEpoch = CborDeserializer.decodeUnsignedInteger(fields.get(3)).asLong();
      effectiveEnd = CborDeserializer.decodeUnsignedInteger(fields.get(4)).asLong();
      endEntryId = CborDeserializer.decodeNullable(fields.get(5),
          CborDeserializer::decodeByteString);
      items = CborDeserializer.decodeArrayAtMost(fields.get(6), limits.getMaxEntries() + 1);
      nextEpoch = CborDeserializer.decodeNullable(fields.get(7),
          item -> CborDeserializer.decodeUnsignedInteger(item).asLong());
    } catch (TrustBaseException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TrustBaseEncodingException("Malformed GetTrustBase response: " + e.getMessage());
    }

    if (version != RESPONSE_VERSION) {
      throw new TrustBaseEncodingException("The response version is not 1.");
    }
    if (Long.compareUnsigned(networkValue, 0xFFFFL) > 0 || networkValue == 0) {
      throw new TrustBaseEncodingException("Invalid network id.");
    }
    NetworkId network = NetworkId.fromId((short) networkValue);
    TrustBaseStatus status = TrustBaseStatus.fromWire(statusValue);
    if (status == null) {
      throw new TrustBaseEncodingException("The status is out of range.");
    }
    if (endEntryId != null && endEntryId.length != TrustBaseEntry.ID_LENGTH) {
      throw new TrustBaseEncodingException("The end entry id is not 32 bytes.");
    }
    if (items.size() > limits.getMaxEntries()) {
      throw new TrustBaseResponseException("The entry count exceeds the bound.");
    }
    if (!network.equals(expectedNetwork)) {
      throw new TrustBaseResponseException("A response for another network.");
    }

    if (status != TrustBaseStatus.OK) {
      if (!items.isEmpty() || nextEpoch != null) {
        throw new TrustBaseResponseException(
            "A status other than OK carries entries or a cursor.");
      }
      return new TrustBaseRange(status, network, fromEpoch, effectiveEnd, endEntryId,
          new ArrayList<>(), null);
    }
    if (items.isEmpty()) {
      throw new TrustBaseResponseException("An OK response without entries.");
    }
    if (endEntryId == null) {
      throw new TrustBaseResponseException("No end entry id.");
    }

    List<TrustBaseEntry> entries = new ArrayList<>(items.size());
    byte[] previousId = null;
    TrustBaseEntry previous = null;
    for (byte[] item : items) {
      if (item.length > TrustBaseEntry.MAX_ENTRY_BYTES) {
        throw new TrustBaseResponseException("An entry exceeds the size bound.");
      }
      TrustBaseEntry entry;
      try {
        entry = TrustBaseEntry.decode(item);
      } catch (TrustBaseException e) {
        throw new TrustBaseEncodingException("An embedded entry is not a version 2 entry.");
      }
      if (!entry.getNetworkId().equals(network)) {
        throw new TrustBaseResponseException("An entry of another network.");
      }
      if (previous == null) {
        if (entry.getEpoch() != fromEpoch) {
          throw new TrustBaseResponseException("The first entry is not at the page's start epoch.");
        }
      } else {
        if (previous.getEpoch() == -1L || entry.getEpoch() != previous.getEpoch() + 1) {
          throw new TrustBaseResponseException("The entry epochs are not consecutive.");
        }
        if (!Arrays.equals(entry.getPreviousEntryId(), previousId)) {
          throw new TrustBaseResponseException("An entry does not link to its predecessor.");
        }
      }
      previousId = entry.entryId();
      previous = entry;
      entries.add(entry);
    }

    long last = previous.getEpoch();
    if (nextEpoch == null) {
      if (last != effectiveEnd) {
        throw new TrustBaseResponseException(
            "The page ends before the effective end without a cursor.");
      }
    } else if (last == -1L || nextEpoch != last + 1
        || Long.compareUnsigned(nextEpoch, effectiveEnd) > 0) {
      throw new TrustBaseResponseException("The cursor does not follow the last entry.");
    }
    if (last == effectiveEnd && !Arrays.equals(endEntryId, previousId)) {
      throw new TrustBaseResponseException("The end entry id differs from the last entry.");
    }
    return new TrustBaseRange(status, network, fromEpoch, effectiveEnd, endEntryId, entries,
        nextEpoch);
  }
}
