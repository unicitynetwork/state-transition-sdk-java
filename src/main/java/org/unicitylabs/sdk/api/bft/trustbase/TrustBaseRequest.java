package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.api.NetworkId;

/** One GetTrustBase request (bft-core {@code gettrustbase.Request}, without the end epoch, which the SDK never sets). */
public final class TrustBaseRequest {

  private final long fromEpoch;
  private final int maxEntries;
  private final NetworkId networkId;
  private final Snapshot snapshot;

  /**
   * Create a request.
   *
   * @param fromEpoch first epoch wanted (unsigned): the latest held epoch + 1, or the cursor of the previous page
   * @param maxEntries most entries wanted in the response
   * @param networkId the network
   * @param snapshot the snapshot the first page announced, echoed on later pages; may be null
   */
  public TrustBaseRequest(long fromEpoch, int maxEntries, NetworkId networkId, Snapshot snapshot) {
    this.fromEpoch = fromEpoch;
    this.maxEntries = maxEntries;
    this.networkId = Objects.requireNonNull(networkId, "networkId cannot be null");
    this.snapshot = snapshot;
  }

  /**
   * Get the first epoch wanted.
   *
   * @return the epoch, an unsigned 64-bit value
   */
  public long getFromEpoch() {
    return this.fromEpoch;
  }

  /**
   * Get the most entries wanted.
   *
   * @return the bound
   */
  public int getMaxEntries() {
    return this.maxEntries;
  }

  /**
   * Get the network.
   *
   * @return the network
   */
  public NetworkId getNetworkId() {
    return this.networkId;
  }

  /**
   * Get the echoed snapshot.
   *
   * @return the snapshot, or null on a first page
   */
  public Snapshot getSnapshot() {
    return this.snapshot;
  }

  /** The end epoch and its entry id that the first page of a paged range announced, echoed on every later page. */
  public static final class Snapshot {

    private final long end;
    private final byte[] entryId;

    /**
     * Create a snapshot.
     *
     * @param end the announced last epoch (unsigned)
     * @param entryId identity of the entry at {@code end}
     */
    public Snapshot(long end, byte[] entryId) {
      this.end = end;
      this.entryId = Arrays.copyOf(Objects.requireNonNull(entryId, "entryId cannot be null"),
          entryId.length);
    }

    /**
     * Get the announced last epoch.
     *
     * @return the epoch, an unsigned 64-bit value
     */
    public long getEnd() {
      return this.end;
    }

    /**
     * Get the identity of the entry at the end epoch.
     *
     * @return a copy of the entry id
     */
    public byte[] getEntryId() {
      return Arrays.copyOf(this.entryId, this.entryId.length);
    }
  }
}
