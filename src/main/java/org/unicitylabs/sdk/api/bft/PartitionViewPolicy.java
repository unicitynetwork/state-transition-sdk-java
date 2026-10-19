package org.unicitylabs.sdk.api.bft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Optional stricter constraints a partition view is additionally checked against.
 *
 * <p>A view is authenticated by its commitment under the enclosing seal; its authenticated leaves
 * establish the topology. These pins come from the verifier's own configuration, never from the
 * response that carries the view, and only narrow what is accepted. Every pinned shard must be a
 * shard of the view, a shard is pinned at most once, and anything else is rejected rather than
 * ignored (a pin that is not consulted would make the policy fail open). Shards without a pin are
 * not constrained.
 *
 * <p>Immutable. The identity of a policy ({@link #getIdentity()}) is part of every cache and
 * session key derived from verdicts that applied it.
 */
public final class PartitionViewPolicy {

  private static final PartitionViewPolicy NONE =
      new PartitionViewPolicy(null, Collections.emptyList());

  private final List<ShardId> scheme;
  private final List<ShardConfigurationPin> pins;
  private final String identity;

  private PartitionViewPolicy(List<ShardId> scheme, List<ShardConfigurationPin> pins) {
    this.scheme = scheme;
    this.pins = pins;
    this.identity = computeIdentity(scheme, pins);
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
   * @throws PartitionViewException with reason TOPOLOGY if the scheme is not a valid scheme, or a
   *     pin already set is not a shard of it
   */
  public PartitionViewPolicy withScheme(List<ShardId> scheme) {
    Objects.requireNonNull(scheme, "scheme cannot be null");
    List<ShardId> copy = Collections.unmodifiableList(new ArrayList<>(scheme));
    List<ShardId> order = ShardTopology.canonicalOrder(copy, PartitionView.MAX_LEAVES,
        PartitionView.MAX_SHARD_ID_BITS, null);
    for (ShardConfigurationPin pin : pins) {
      if (!order.contains(pin.getShard())) {
        throw new PartitionViewException(PartitionViewException.Reason.TOPOLOGY,
            "A pinned shard is not a shard of the pinned scheme.");
      }
    }
    return new PartitionViewPolicy(copy, pins);
  }

  /**
   * Pins shard configuration hashes.
   *
   * @param pins the pins
   * @return new policy
   * @throws PartitionViewException with reason TOPOLOGY if a shard is pinned twice, or is not a
   *     shard of the scheme set before
   */
  public PartitionViewPolicy withPins(List<ShardConfigurationPin> pins) {
    Objects.requireNonNull(pins, "pins cannot be null");
    List<ShardConfigurationPin> copy = Collections.unmodifiableList(new ArrayList<>(pins));
    Set<ShardId> seen = new HashSet<>();
    List<ShardId> order = scheme == null ? null
        : ShardTopology.canonicalOrder(scheme, PartitionView.MAX_LEAVES,
            PartitionView.MAX_SHARD_ID_BITS, null);
    for (ShardConfigurationPin pin : copy) {
      Objects.requireNonNull(pin, "pin cannot be null");
      if (!seen.add(pin.getShard())) {
        throw new PartitionViewException(PartitionViewException.Reason.TOPOLOGY,
            String.format("Shard \"%s\" is pinned more than once.", pin.getShard()));
      }
      if (order != null && !order.contains(pin.getShard())) {
        throw new PartitionViewException(PartitionViewException.Reason.TOPOLOGY,
            "A pinned shard is not a shard of the pinned scheme.");
      }
    }
    return new PartitionViewPolicy(scheme, copy);
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

  /**
   * Canonical identity of the constraints: the scheme in canonical order and the pins sorted by
   * shard. Equal identities mean equal acceptance decisions.
   *
   * @return identity text
   */
  public String getIdentity() {
    return identity;
  }

  /**
   * Checks a shard certificate against the policy: the shard is a shard of the pinned scheme and,
   * if its configuration is pinned, the configuration hash equals the pin.
   *
   * @param shard the shard of the certificate
   * @param shardConfigurationHash the certificate's shard configuration hash
   * @return description of the violation, or null if the policy admits the shard
   */
  public String violation(ShardId shard, byte[] shardConfigurationHash) {
    if (scheme != null) {
      List<ShardId> order = ShardTopology.canonicalOrder(scheme, PartitionView.MAX_LEAVES,
          PartitionView.MAX_SHARD_ID_BITS, null);
      if (!order.contains(shard)) {
        return String.format("Shard \"%s\" is not a shard of the pinned scheme.", shard);
      }
    }
    for (ShardConfigurationPin pin : pins) {
      if (pin.getShard().equals(shard)
          && !Arrays.equals(pin.getHash(), shardConfigurationHash)) {
        return String.format(
            "Shard \"%s\": configuration hash does not match the pinned configuration.", shard);
      }
    }
    return null;
  }

  private static String computeIdentity(List<ShardId> scheme, List<ShardConfigurationPin> pins) {
    StringBuilder text = new StringBuilder("scheme=");
    if (scheme == null) {
      text.append('-');
    } else if (scheme.isEmpty()) {
      text.append("[]");
    } else {
      List<String> order = new ArrayList<>();
      for (ShardId id : ShardTopology.canonicalOrder(scheme, PartitionView.MAX_LEAVES,
          PartitionView.MAX_SHARD_ID_BITS, null)) {
        order.add(id.toString());
      }
      text.append('[').append(String.join(",", order)).append(']');
    }
    List<String> pinned = new ArrayList<>();
    for (ShardConfigurationPin pin : pins) {
      pinned.add(pin.getShard() + ":" + HexConverter.encode(pin.getHash()));
    }
    Collections.sort(pinned);
    text.append(";pins=").append(String.join(",", pinned));
    return text.toString();
  }
}
