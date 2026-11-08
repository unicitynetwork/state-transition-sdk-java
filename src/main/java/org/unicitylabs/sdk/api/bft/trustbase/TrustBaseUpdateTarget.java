package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.Objects;
import java.util.Optional;
import org.unicitylabs.sdk.api.NetworkId;

/**
 * What the updater extends: a {@link TrustBaseHistory}, or a session that adds its own rules (an
 * authenticated fork closes it).
 */
public interface TrustBaseUpdateTarget {

  /**
   * Get the network of the held entries.
   *
   * @return the network, empty while the target holds nothing
   */
  Optional<NetworkId> getNetworkId();

  /**
   * Get the latest held entry.
   *
   * @return the tip, empty while the target holds nothing
   */
  Optional<TrustBaseEntry> getTip();

  /**
   * Add an entry, authenticating it from the entries held.
   *
   * @param entry decoded entry
   * @return whether the entry was added or already held
   * @throws TrustBaseException if the target refuses the entry
   */
  TrustBaseAppendOutcome append(TrustBaseEntry entry);

  /**
   * View a history as an update target.
   *
   * @param history the history to extend in place
   * @return the target
   */
  static TrustBaseUpdateTarget of(TrustBaseHistory history) {
    Objects.requireNonNull(history, "history cannot be null");
    return new TrustBaseUpdateTarget() {
      @Override
      public Optional<NetworkId> getNetworkId() {
        return history.getNetworkId();
      }

      @Override
      public Optional<TrustBaseEntry> getTip() {
        return history.getTip();
      }

      @Override
      public TrustBaseAppendOutcome append(TrustBaseEntry entry) {
        return history.append(entry);
      }
    };
  }
}
