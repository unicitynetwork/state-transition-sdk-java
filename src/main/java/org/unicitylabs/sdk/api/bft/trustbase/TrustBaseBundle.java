package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.util.UnsignedLongs;

/**
 * A complete Unicity Trust Base release bundle: the GetTrustBase response (CBOR tag 39015) with
 * status OK, starting at the genesis entry, ending at the release tip, with no continuation
 * cursor. The reference is bft-core {@code gettrustbase.DecodeBundle}.
 *
 * <p>Wire form: tag 39015 over {@code [1, networkId, 0, fromEpoch, effectiveEnd, endEntryId,
 * [entry...], null]}, each entry a complete tag-39000 {@link TrustBaseEntry}, consecutive and
 * ascending from the genesis, each linked to its predecessor by entry id. A one-entry bundle holds
 * a genesis entry only.
 *
 * <p>Decoding checks the structure and the links, not the signatures: the history that installs
 * the bundle authenticates every entry after the genesis, and the genesis against a pinned entry
 * id.
 */
public final class TrustBaseBundle {

  private final NetworkId networkId;
  private final List<TrustBaseEntry> entries;
  private final List<byte[]> raw;

  private TrustBaseBundle(NetworkId networkId, List<TrustBaseEntry> entries, List<byte[]> raw) {
    this.networkId = networkId;
    this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    this.raw = raw;
  }

  /**
   * Get the network.
   *
   * @return network id
   */
  public NetworkId getNetworkId() {
    return this.networkId;
  }

  /**
   * Get the entries, ascending from the genesis.
   *
   * @return immutable list
   */
  public List<TrustBaseEntry> getEntries() {
    return this.entries;
  }

  /**
   * Get the canonical bytes of each entry, ascending from the genesis.
   *
   * @return copies
   */
  public List<byte[]> getRaw() {
    List<byte[]> copy = new ArrayList<>(this.raw.size());
    for (byte[] bytes : this.raw) {
      copy.add(Arrays.copyOf(bytes, bytes.length));
    }
    return copy;
  }

  /**
   * Get the release tip.
   *
   * @return the last entry
   */
  public TrustBaseEntry getTip() {
    return this.entries.get(this.entries.size() - 1);
  }

  /**
   * Strictly decode a complete bundle.
   *
   * @param bytes tagged bundle
   * @param limits size budget, checked before allocation
   * @return the bundle
   * @throws TrustBaseEncodingException if the bytes (or an entry) are not the deterministic
   *     encoding, or exceed the budget
   * @throws TrustBaseInvalidException if an embedded entry fails a structure rule
   * @throws TrustBaseBundleException if the response is not a complete, linked history from a
   *     genesis entry
   */
  public static TrustBaseBundle decode(byte[] bytes, TrustBaseBundleLimits limits) {
    Objects.requireNonNull(bytes, "bytes cannot be null");
    Objects.requireNonNull(limits, "limits cannot be null");
    if (bytes.length > limits.getMaxBytes()) {
      throw new TrustBaseEncodingException(
          bytes.length + " bytes exceed the budget of " + limits.getMaxBytes() + ".");
    }

    TrustBaseResponseWire.Fields header = TrustBaseResponseWire.parse(bytes, limits.getMaxEntries());
    if (header.getStatus() != TrustBaseResponseWire.STATUS_OK) {
      throw new TrustBaseBundleException(
          "Status " + Long.toUnsignedString(header.getStatus()) + ": not a complete bundle.");
    }
    if (header.getNextEpoch() != null) {
      throw new TrustBaseBundleException("A continuation cursor: not a complete bundle.");
    }
    List<byte[]> rawEntries = header.getEntries();
    if (rawEntries.isEmpty()) {
      throw new TrustBaseBundleException("An OK response without entries.");
    }
    byte[] endEntryId = header.getEndEntryId();
    if (endEntryId == null) {
      throw new TrustBaseBundleException("No end entry id.");
    }

    List<TrustBaseEntry> entries = new ArrayList<>(rawEntries.size());
    byte[] previousId = null;
    for (int index = 0; index < rawEntries.size(); index++) {
      byte[] rawEntry = rawEntries.get(index);
      if (rawEntry.length > limits.getMaxEntryBytes()) {
        throw new TrustBaseEncodingException("Entry " + index + " of " + rawEntry.length + " bytes.");
      }
      TrustBaseEntry entry = TrustBaseEntry.decode(rawEntry);
      if (entry.getNetworkId().getId() != header.getNetwork()) {
        throw new TrustBaseBundleException("Entry " + index + " of network "
            + entry.getNetworkId().getId() + " in a bundle for " + header.getNetwork() + ".");
      }
      if (index == 0) {
        if (!entry.isGenesis()) {
          throw new TrustBaseBundleException("Epoch " + UnsignedLongs.toDecimal(entry.getEpoch())
              + " is not a genesis entry (a partial range).");
        }
        if (entry.getEpoch() != header.getFromEpoch()) {
          throw new TrustBaseBundleException("First entry is epoch "
              + UnsignedLongs.toDecimal(entry.getEpoch()) + ", the bundle starts at "
              + UnsignedLongs.toDecimal(header.getFromEpoch()) + ".");
        }
      } else {
        TrustBaseEntry previous = entries.get(index - 1);
        if (entry.getEpoch() != previous.getEpoch() + 1) {
          throw new TrustBaseBundleException("Epoch " + UnsignedLongs.toDecimal(entry.getEpoch())
              + " does not follow " + UnsignedLongs.toDecimal(previous.getEpoch()) + ".");
        }
        if (!Arrays.equals(entry.getPreviousEntryId(), previousId)) {
          throw new TrustBaseBundleException("Epoch " + UnsignedLongs.toDecimal(entry.getEpoch())
              + " does not link to its predecessor.");
        }
      }
      previousId = entry.entryId();
      entries.add(entry);
    }

    TrustBaseEntry tip = entries.get(entries.size() - 1);
    if (tip.getEpoch() != header.getEffectiveEnd()) {
      throw new TrustBaseBundleException("The bundle ends at epoch "
          + UnsignedLongs.toDecimal(tip.getEpoch()) + ", its header says "
          + UnsignedLongs.toDecimal(header.getEffectiveEnd()) + ".");
    }
    if (!Arrays.equals(endEntryId, previousId)) {
      throw new TrustBaseBundleException("The end entry id differs from the last entry.");
    }
    return new TrustBaseBundle(entries.get(0).getNetworkId(), entries, rawEntries);
  }

  /**
   * Encode entries as a complete bundle (status OK, no cursor, end entry id of the last entry).
   * The inverse of {@link #decode}: a decoded bundle re-encodes to the same bytes. The entries are
   * not authenticated here; a bundle that does not link is not produced.
   *
   * @param entries consecutive entries ascending from the genesis, at least one
   * @return tagged bundle
   * @throws TrustBaseBundleException if the list is empty or the entries do not form one linked
   *     chain of one network
   */
  public static byte[] encode(List<TrustBaseEntry> entries) {
    Objects.requireNonNull(entries, "entries cannot be null");
    if (entries.isEmpty()) {
      throw new TrustBaseBundleException("A bundle needs at least one entry.");
    }

    byte[] previousId = null;
    List<byte[]> raw = new ArrayList<>(entries.size());
    for (int index = 0; index < entries.size(); index++) {
      TrustBaseEntry entry = entries.get(index);
      if (index == 0) {
        if (!entry.isGenesis()) {
          throw new TrustBaseBundleException("Entry 0 does not continue the chain.");
        }
      } else if (entry.getEpoch() != entries.get(index - 1).getEpoch() + 1) {
        throw new TrustBaseBundleException("Entry " + index + " does not continue the chain.");
      }
      if (previousId != null && !Arrays.equals(entry.getPreviousEntryId(), previousId)) {
        throw new TrustBaseBundleException("Entry " + index + " does not link to its predecessor.");
      }
      if (entry.getNetworkId().getId() != entries.get(0).getNetworkId().getId()) {
        throw new TrustBaseBundleException("Entry " + index + " is of another network.");
      }
      previousId = entry.entryId();
      raw.add(entry.encode());
    }

    TrustBaseEntry tip = entries.get(entries.size() - 1);
    return TrustBaseResponseWire.encode(new TrustBaseResponseWire.Fields(tip.getEpoch(),
        previousId, raw, entries.get(0).getEpoch(), tip.getNetworkId().getId(), null,
        TrustBaseResponseWire.STATUS_OK));
  }
}
