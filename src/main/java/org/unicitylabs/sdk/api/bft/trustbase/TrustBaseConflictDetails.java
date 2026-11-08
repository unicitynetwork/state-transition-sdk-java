package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.Arrays;

/** What is known about two different entries of one epoch: bounded, identities and optionally the entries. */
public final class TrustBaseConflictDetails {

  private final boolean authenticated;
  private final long epoch;
  private final byte[] heldEntry;
  private final byte[] heldEntryId;
  private final int network;
  private final byte[] offeredEntry;
  private final byte[] offeredEntryId;

  /**
   * Create the details.
   *
   * @param authenticated whether the offered entry is signed by a quorum of its predecessor
   * @param epoch epoch of both entries, unsigned
   * @param heldEntry the held entry bytes, or null
   * @param heldEntryId id of the held entry
   * @param network network id of the offered entry
   * @param offeredEntry the offered entry bytes, or null
   * @param offeredEntryId id of the offered entry
   */
  public TrustBaseConflictDetails(boolean authenticated, long epoch, byte[] heldEntry,
      byte[] heldEntryId, int network, byte[] offeredEntry, byte[] offeredEntryId) {
    this.authenticated = authenticated;
    this.epoch = epoch;
    this.heldEntry = heldEntry == null ? null : Arrays.copyOf(heldEntry, heldEntry.length);
    this.heldEntryId = Arrays.copyOf(heldEntryId, heldEntryId.length);
    this.network = network;
    this.offeredEntry = offeredEntry == null ? null : Arrays.copyOf(offeredEntry, offeredEntry.length);
    this.offeredEntryId = Arrays.copyOf(offeredEntryId, offeredEntryId.length);
  }

  /**
   * Tell whether the offered entry was authenticated by a quorum of its predecessor.
   *
   * @return true if authenticated; false if only its identity differs
   */
  public boolean isAuthenticated() {
    return this.authenticated;
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
   * Get the held entry bytes.
   *
   * @return copy, or null when not kept
   */
  public byte[] getHeldEntry() {
    return this.heldEntry == null ? null : Arrays.copyOf(this.heldEntry, this.heldEntry.length);
  }

  /**
   * Get the id of the held entry.
   *
   * @return copy
   */
  public byte[] getHeldEntryId() {
    return Arrays.copyOf(this.heldEntryId, this.heldEntryId.length);
  }

  /**
   * Get the network id.
   *
   * @return network id
   */
  public int getNetwork() {
    return this.network;
  }

  /**
   * Get the offered entry bytes.
   *
   * @return copy, or null when not kept
   */
  public byte[] getOfferedEntry() {
    return this.offeredEntry == null ? null
        : Arrays.copyOf(this.offeredEntry, this.offeredEntry.length);
  }

  /**
   * Get the id of the offered entry.
   *
   * @return copy
   */
  public byte[] getOfferedEntryId() {
    return Arrays.copyOf(this.offeredEntryId, this.offeredEntryId.length);
  }
}
