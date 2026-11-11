package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.unicitylabs.sdk.api.NetworkId;

/**
 * The read-only face of a history, as verification uses it: the held entries from the pinned
 * genesis, each seal being placed in the entry of its own epoch. Implemented by
 * {@link TrustBaseHistory} and by the cache session (whose view fails once an authenticated fork
 * closed the session). Each method answers from one consistent chain, and the chain only grows.
 */
public interface TrustBaseHistoryView {

  /**
   * Get the network of the held entries.
   *
   * @return the network, empty while the view holds no entry
   */
  Optional<NetworkId> getNetworkId();

  /**
   * Get the latest held entry.
   *
   * @return the tip, empty while the view holds no entry
   */
  Optional<TrustBaseEntry> getTip();

  /**
   * Get a snapshot of the held entries.
   *
   * @return immutable list, ascending from the genesis
   */
  List<TrustBaseEntry> getEntries();

  /**
   * Get the held entry of an epoch.
   *
   * @param epoch epoch, unsigned bits
   * @return the entry, empty when the epoch is before the genesis or after the tip
   */
  Optional<TrustBaseEntry> entryForEpoch(long epoch);

  /**
   * Get the round at which the held successor of an epoch starts: the exclusive end of the epoch's
   * interval (a round is in the epoch from its start round up to, not including, this round).
   *
   * @param epoch epoch, unsigned bits
   * @return start round of the entry for {@code epoch + 1}, empty when none is held
   */
  OptionalLong epochEndRound(long epoch);

  /**
   * Export the held history as one complete bundle.
   *
   * @return the tag-39015 bundle
   * @throws TrustBaseBundleException if the view holds no entry
   */
  byte[] toBundle();

  /**
   * Get the pinned entry id of the genesis.
   *
   * @return copy
   */
  byte[] getGenesisPin();
}
