package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.Objects;
import org.unicitylabs.sdk.api.NetworkId;

/** Options of {@link TrustBaseUpdater#update}. */
public final class TrustBaseUpdateOptions {

  private static final TrustBaseUpdateOptions DEFAULTS = new TrustBaseUpdateOptions(2, 200L,
      TrustBaseRangeLimits.PAGE, null, 1L);

  private final int rounds;
  private final long backoffMs;
  private final TrustBaseRangeLimits limits;
  private final NetworkId network;
  private final long genesisEpoch;

  private TrustBaseUpdateOptions(int rounds, long backoffMs, TrustBaseRangeLimits limits,
      NetworkId network, long genesisEpoch) {
    this.rounds = rounds;
    this.backoffMs = backoffMs;
    this.limits = limits;
    this.network = network;
    this.genesisEpoch = genesisEpoch;
  }

  /**
   * The defaults: 2 passes over the sources, 200 ms between passes, the protocol page limits.
   *
   * @return the defaults
   */
  public static TrustBaseUpdateOptions defaults() {
    return DEFAULTS;
  }

  /**
   * Set the passes over the sources before giving up.
   *
   * @param rounds the passes, at least 1
   * @return the changed options
   */
  public TrustBaseUpdateOptions withRounds(int rounds) {
    if (rounds < 1) {
      throw new IllegalArgumentException("At least one round.");
    }
    return new TrustBaseUpdateOptions(rounds, this.backoffMs, this.limits, this.network,
        this.genesisEpoch);
  }

  /**
   * Set the pause between passes.
   *
   * @param backoffMs the pause in milliseconds, not negative
   * @return the changed options
   */
  public TrustBaseUpdateOptions withBackoffMs(long backoffMs) {
    if (backoffMs < 0) {
      throw new IllegalArgumentException("The backoff cannot be negative.");
    }
    return new TrustBaseUpdateOptions(this.rounds, backoffMs, this.limits, this.network,
        this.genesisEpoch);
  }

  /**
   * Set the page budget of the response decode.
   *
   * @param limits the budget
   * @return the changed options
   */
  public TrustBaseUpdateOptions withLimits(TrustBaseRangeLimits limits) {
    return new TrustBaseUpdateOptions(this.rounds, this.backoffMs,
        Objects.requireNonNull(limits, "limits cannot be null"), this.network, this.genesisEpoch);
  }

  /**
   * Set the network, required while the target is still empty (it is the target's own network
   * otherwise).
   *
   * @param network the network
   * @return the changed options
   */
  public TrustBaseUpdateOptions withNetwork(NetworkId network) {
    return new TrustBaseUpdateOptions(this.rounds, this.backoffMs, this.limits,
        Objects.requireNonNull(network, "network cannot be null"), this.genesisEpoch);
  }

  /**
   * Set the first epoch of the network, used only while the target is still empty.
   *
   * @param genesisEpoch the epoch (unsigned), default 1
   * @return the changed options
   */
  public TrustBaseUpdateOptions withGenesisEpoch(long genesisEpoch) {
    return new TrustBaseUpdateOptions(this.rounds, this.backoffMs, this.limits, this.network,
        genesisEpoch);
  }

  /**
   * Get the passes over the sources.
   *
   * @return the passes
   */
  public int getRounds() {
    return this.rounds;
  }

  /**
   * Get the pause between passes.
   *
   * @return milliseconds
   */
  public long getBackoffMs() {
    return this.backoffMs;
  }

  /**
   * Get the page budget.
   *
   * @return the budget
   */
  public TrustBaseRangeLimits getLimits() {
    return this.limits;
  }

  /**
   * Get the network given for an empty target.
   *
   * @return the network, or null
   */
  public NetworkId getNetwork() {
    return this.network;
  }

  /**
   * Get the first epoch of the network.
   *
   * @return the epoch (unsigned)
   */
  public long getGenesisEpoch() {
    return this.genesisEpoch;
  }
}
