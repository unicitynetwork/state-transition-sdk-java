package org.unicitylabs.sdk.api.bft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Optional stricter constraints a partition view is additionally checked against.
 *
 * <p>A view is authenticated by its commitment under the enclosing seal; its authenticated leaves
 * establish the topology. These pins come from the verifier's own configuration, never from the
 * response that carries the view, and only narrow what is accepted. Every pinned shard must be a
 * shard of the view, a shard is pinned at most once, and anything else is rejected rather than
 * ignored (a pin that is not consulted would make the policy fail open). Shards without a pin are
 * not constrained.
 */
public final class PartitionViewPolicy {

  private static final PartitionViewPolicy NONE =
      new PartitionViewPolicy(null, Collections.emptyList());

  private final List<ShardId> scheme;
  private final List<ShardConfigurationPin> pins;

  private PartitionViewPolicy(List<ShardId> scheme, List<ShardConfigurationPin> pins) {
    this.scheme = scheme;
    this.pins = pins;
  }

  /**
   * A policy that constrains nothing.
   *
   * @return empty policy
   */
  public static PartitionViewPolicy none() {
    return NONE;
  }

  /**
   * Requires a sharding scheme: empty for a single shard partition, otherwise at least two shards
   * forming a complete prefix-free set.
   *
   * @param scheme the required shard ids
   * @return new policy
   */
  public PartitionViewPolicy withScheme(List<ShardId> scheme) {
    Objects.requireNonNull(scheme, "scheme cannot be null");
    return new PartitionViewPolicy(Collections.unmodifiableList(new ArrayList<>(scheme)), pins);
  }

  /**
   * Pins shard configuration hashes.
   *
   * @param pins the pins
   * @return new policy
   */
  public PartitionViewPolicy withPins(List<ShardConfigurationPin> pins) {
    Objects.requireNonNull(pins, "pins cannot be null");
    return new PartitionViewPolicy(scheme, Collections.unmodifiableList(new ArrayList<>(pins)));
  }

  /**
   * Required scheme.
   *
   * @return shard ids, null if the scheme is not constrained
   */
  public List<ShardId> getScheme() {
    return scheme;
  }

  /**
   * Pinned configuration hashes.
   *
   * @return unmodifiable list of pins
   */
  public List<ShardConfigurationPin> getPins() {
    return pins;
  }
}
