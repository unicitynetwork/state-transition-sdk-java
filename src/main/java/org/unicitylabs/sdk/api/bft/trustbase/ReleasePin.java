package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.api.NetworkId;

/**
 * A release pin: the complete trust base history of a network from its genesis to exactly the
 * pinned tip, and the identities that bind it. It comes from the authenticated software release
 * (or, for a private network, from the application's own configuration), never from a digest shipped
 * beside an untrusted download. Holds copies.
 */
public final class ReleasePin {

  /** Budget of an embedded bundle, a cache and merged bundles: 16 MiB, 4,096 entries, 128 KiB each. */
  public static final TrustBaseBundleLimits DEFAULT_LIMITS =
      new TrustBaseBundleLimits(16 * 1024 * 1024, 4096, TrustBaseEntry.MAX_ENTRY_BYTES);

  private final NetworkId networkId;
  private final long genesisEpoch;
  private final byte[] genesisEntryId;
  private final long pinnedEpoch;
  private final byte[] pinnedEntryId;
  private final byte[] bundle;
  private final byte[] bundleSha256;

  /**
   * Create a pin.
   *
   * @param networkId the network
   * @param genesisEpoch epoch of the genesis entry (unsigned)
   * @param genesisEntryId identity of the genesis entry
   * @param pinnedEpoch epoch of the release tip, the checkpoint (unsigned)
   * @param pinnedEntryId identity of the release tip
   * @param bundle the complete history, genesis to exactly the tip, as a tag-39015 bundle
   * @param bundleSha256 SHA-256 of {@code bundle}
   */
  public ReleasePin(NetworkId networkId, long genesisEpoch, byte[] genesisEntryId,
      long pinnedEpoch, byte[] pinnedEntryId, byte[] bundle, byte[] bundleSha256) {
    this.networkId = Objects.requireNonNull(networkId, "networkId cannot be null");
    this.genesisEpoch = genesisEpoch;
    this.genesisEntryId = copy(genesisEntryId);
    this.pinnedEpoch = pinnedEpoch;
    this.pinnedEntryId = copy(pinnedEntryId);
    this.bundle = copy(bundle);
    this.bundleSha256 = copy(bundleSha256);
  }

  private static byte[] copy(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes cannot be null");
    return Arrays.copyOf(bytes, bytes.length);
  }

  /**
   * A pin derived from a complete tag-39015 bundle (genesis to tip) alone: the network, both epochs
   * and both identities are read from the bundle and the digest is computed. For a local or private
   * network whose own deployment is the source of trust; the bundle is still verified strictly,
   * link by link, when a session opens.
   *
   * @param bundle the complete bundle
   * @return the pin
   * @throws ReleasePinInvalidException if the bundle is not a complete GetTrustBase response (a
   *     continuation, a status other than OK), does not start at a genesis entry, is empty, is over
   *     the default budget or is malformed
   */
  public static ReleasePin fromBundle(byte[] bundle) {
    Objects.requireNonNull(bundle, "bundle cannot be null");
    TrustBaseBundle parsed;
    try {
      parsed = TrustBaseBundle.decode(bundle, DEFAULT_LIMITS);
      parsed.getEntries().get(0).verifyGenesis();
    } catch (TrustBaseException e) {
      throw new ReleasePinInvalidException("The bundle is not a complete GetTrustBase response "
          + "starting at a genesis entry.", e);
    }
    TrustBaseEntry first = parsed.getEntries().get(0);
    TrustBaseEntry last = parsed.getTip();
    return new ReleasePin(parsed.getNetworkId(), first.getEpoch(), first.entryId(), last.getEpoch(),
        last.entryId(), bundle, TrustBaseCacheKey.sha256(bundle));
  }

  /**
   * Verify the pin and build the history it embeds: digest, structure, every predecessor signature,
   * both identities and both epochs.
   *
   * @param limits budget of the embedded bundle
   * @return the verified pin
   * @throws ReleasePinInvalidException if anything about the pin does not hold
   */
  public VerifiedReleasePin verify(TrustBaseBundleLimits limits) {
    try {
      if (!Arrays.equals(TrustBaseCacheKey.sha256(this.bundle), this.bundleSha256)) {
        throw new ReleasePinInvalidException("The bundle digest differs from the pin.");
      }
      TrustBaseBundle decoded = TrustBaseBundle.decode(this.bundle, limits);
      TrustBaseHistory history = TrustBaseHistory.fromBundle(decoded, this.genesisEntryId);
      if (!history.getNetworkId().get().equals(this.networkId)) {
        throw new ReleasePinInvalidException("The embedded bundle is of another network.");
      }
      TrustBaseEntry genesis = history.getEntries().get(0);
      if (genesis.getEpoch() != this.genesisEpoch) {
        throw new ReleasePinInvalidException("The genesis epoch differs from the pin.");
      }
      TrustBaseEntry tip = history.getTip().get();
      if (tip.getEpoch() != this.pinnedEpoch
          || !Arrays.equals(tip.entryId(), this.pinnedEntryId)) {
        throw new ReleasePinInvalidException("The tip differs from the pin.");
      }
      return new VerifiedReleasePin(this, history);
    } catch (ReleasePinInvalidException e) {
      throw e;
    } catch (TrustBaseException e) {
      throw new ReleasePinInvalidException("The embedded bundle does not verify: "
          + e.getMessage(), e);
    }
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
   * Get the epoch of the genesis entry.
   *
   * @return the epoch (unsigned)
   */
  public long getGenesisEpoch() {
    return this.genesisEpoch;
  }

  /**
   * Get the identity of the genesis entry.
   *
   * @return a copy
   */
  public byte[] getGenesisEntryId() {
    return copy(this.genesisEntryId);
  }

  /**
   * Get the epoch of the release tip, the checkpoint.
   *
   * @return the epoch (unsigned)
   */
  public long getPinnedEpoch() {
    return this.pinnedEpoch;
  }

  /**
   * Get the identity of the release tip.
   *
   * @return a copy
   */
  public byte[] getPinnedEntryId() {
    return copy(this.pinnedEntryId);
  }

  /**
   * Get the embedded bundle.
   *
   * @return a copy
   */
  public byte[] getBundle() {
    return copy(this.bundle);
  }

  /**
   * Get the SHA-256 of the bundle.
   *
   * @return a copy
   */
  public byte[] getBundleSha256() {
    return copy(this.bundleSha256);
  }

  /** A pin that has been verified, with a fresh history holding its entries. */
  public static final class VerifiedReleasePin {

    private final ReleasePin pin;
    private final TrustBaseHistory history;

    private VerifiedReleasePin(ReleasePin pin, TrustBaseHistory history) {
      this.pin = pin;
      this.history = history;
    }

    /**
     * Get the pin.
     *
     * @return the pin
     */
    public ReleasePin getPin() {
      return this.pin;
    }

    /**
     * Get the history the pin embeds.
     *
     * @return a fresh history; the caller owns it
     */
    public TrustBaseHistory getHistory() {
      return this.history;
    }

    /**
     * The entry id of the checkpoint at an epoch.
     *
     * @param epoch the epoch (unsigned)
     * @return the entry id, or null outside the pinned history
     */
    public byte[] idAt(long epoch) {
      return this.history.entryForEpoch(epoch).map(TrustBaseEntry::entryId).orElse(null);
    }

    /**
     * Whether a candidate continues this pin: the same network and genesis, containing this
     * checkpoint at this epoch, at an equal or later pinned epoch.
     *
     * @param candidate the verified candidate
     * @return true if the candidate extends this pin
     */
    public boolean isExtendedBy(VerifiedReleasePin candidate) {
      byte[] id = candidate.idAt(this.pin.pinnedEpoch);
      return candidate.pin.networkId.equals(this.pin.networkId)
          && Arrays.equals(candidate.pin.genesisEntryId, this.pin.genesisEntryId)
          && id != null && Arrays.equals(id, this.pin.pinnedEntryId);
    }
  }
}
