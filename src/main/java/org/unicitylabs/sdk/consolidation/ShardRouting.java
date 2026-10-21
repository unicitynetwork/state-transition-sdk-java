package org.unicitylabs.sdk.consolidation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.AggregationPartition;
import org.unicitylabs.sdk.api.bft.PartitionView;
import org.unicitylabs.sdk.api.bft.PartitionViewException;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.ShardConfigurationPin;
import org.unicitylabs.sdk.api.bft.ShardId;

/**
 * Immutable routing from a state id to its shard endpoint, from the admitted shard prefix
 * topology. No modulo rule, no inference from timestamps. The routing is static trusted
 * configuration: invalid configurations (incomplete or overlapping scheme, endpoints outside the
 * scheme, duplicates, malformed pins) are rejected when it is built, never when a token is
 * consolidated. The partition is the fixed aggregation partition, it is not configurable.
 */
public final class ShardRouting {

  private final List<ShardEndpoint> endpoints;
  private final List<ShardId> scheme;
  private final List<ShardId> order;
  private final List<ShardConfigurationPin> pins;
  private final PartitionViewPolicy viewPolicy;

  /**
   * Builds the routing.
   *
   * @param endpoints endpoints of the shards that may take part; their ids must be shards of the
   *     scheme
   * @param scheme complete sharding scheme of the partition: empty for a single shard partition
   * @param pins optional pinned configuration hash per shard, a stricter policy; every shard must
   *     be in the scheme, at most once; null or empty for none
   * @throws ConsolidationException of kind INPUT if the configuration is invalid
   */
  public ShardRouting(List<ShardEndpoint> endpoints, List<ShardId> scheme,
      List<ShardConfigurationPin> pins) {
    Objects.requireNonNull(endpoints, "endpoints cannot be null");
    Objects.requireNonNull(scheme, "scheme cannot be null");
    List<ShardConfigurationPin> pinList = pins == null ? Collections.emptyList() : pins;

    PartitionViewPolicy policy;
    List<ShardId> canonical;
    try {
      canonical = PartitionView.canonicalShardOrder(scheme);
      policy = PartitionViewPolicy.none().withScheme(scheme).withPins(pinList);
    } catch (PartitionViewException e) {
      throw input("Invalid sharding scheme or pins: " + e.getMessage(), e);
    }
    if (endpoints.isEmpty()) {
      throw input("At least one endpoint is required.", null);
    }
    Set<ShardId> seen = new HashSet<>();
    for (ShardEndpoint endpoint : endpoints) {
      Objects.requireNonNull(endpoint, "endpoint cannot be null");
      if (!canonical.contains(endpoint.getShard())) {
        throw input(String.format("Endpoint shard \"%s\" is not in the scheme.",
            label(endpoint.getShard())), null);
      }
      if (!seen.add(endpoint.getShard())) {
        throw input(String.format("Duplicate endpoint for shard \"%s\".",
            label(endpoint.getShard())), null);
      }
    }

    this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
    this.scheme = Collections.unmodifiableList(new ArrayList<>(scheme));
    this.order = canonical;
    this.pins = Collections.unmodifiableList(new ArrayList<>(pinList));
    this.viewPolicy = policy;
  }

  /**
   * Routing of a single shard partition served by one endpoint.
   *
   * @param client client of the shard aggregator
   * @return routing
   */
  public static ShardRouting single(AggregatorClient client) {
    return new ShardRouting(List.of(new ShardEndpoint(ShardId.empty(), client)),
        Collections.emptyList(), null);
  }

  /**
   * The shards of the scheme in canonical (left to right) order; one empty shard if unsharded.
   *
   * @return shard ids
   */
  public List<ShardId> getOrder() {
    return order;
  }

  /**
   * The configured scheme: empty for a single shard partition.
   *
   * @return shard ids
   */
  public List<ShardId> getScheme() {
    return scheme;
  }

  /**
   * The configured pinned configuration hashes.
   *
   * @return unmodifiable list of pins
   */
  public List<ShardConfigurationPin> getPins() {
    return pins;
  }

  /**
   * The partition: the aggregation partition, read-only and not configurable.
   *
   * @return partition id
   */
  public long getPartitionId() {
    return AggregationPartition.AGGREGATION_PARTITION_ID;
  }

  /**
   * The constraints partition views of this routing are authenticated under: the configured scheme
   * and pins.
   *
   * @return view policy
   */
  public PartitionViewPolicy getViewPolicy() {
    return viewPolicy;
  }

  /**
   * Endpoint serving a state's shard.
   *
   * @param stateId state id
   * @return endpoint
   * @throws ConsolidationException of kind INPUT if no configured endpoint serves the state
   */
  public ShardEndpoint route(StateId stateId) {
    byte[] data = stateId.getData();
    for (ShardEndpoint endpoint : endpoints) {
      if (endpoint.getShard().isEmpty() || endpoint.getShard().isPrefixOf(data)) {
        return endpoint;
      }
    }
    throw input("No configured endpoint serves " + stateId + ".", null);
  }

  private static String label(ShardId shard) {
    return shard.isEmpty() ? "(root)" : shard.toString();
  }

  private static ConsolidationException input(String message, Throwable cause) {
    return ConsolidationException.of(ConsolidationException.Kind.INPUT,
        ConsolidationOutcome.INVALID_INPUT, message, null, cause);
  }
}
