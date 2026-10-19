package org.unicitylabs.sdk.consolidation;

import java.util.Objects;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.bft.ShardId;

/**
 * Aggregator of one shard, configured by the application. URLs and credentials live in the client
 * the application built; nothing a token or a response says can add or replace an endpoint.
 *
 * <p>Consolidation needs the batch capability ({@link org.unicitylabs.sdk.api.BatchAggregatorClient}).
 * A client without it is accepted here, so a routing can be built for any client, and a
 * consolidation that would use it ends as {@link ProofUnavailableReason#UNSUPPORTED_CAPABILITY}
 * before any request is sent: a single-proof call is never emulated as a batch and nothing is
 * probed.
 */
public final class ShardEndpoint {

  private final ShardId shard;
  private final AggregatorClient client;

  /**
   * Creates an endpoint.
   *
   * @param shard the shard it serves
   * @param client client of the shard's aggregator
   */
  public ShardEndpoint(ShardId shard, AggregatorClient client) {
    this.shard = Objects.requireNonNull(shard, "shard cannot be null");
    this.client = Objects.requireNonNull(client, "client cannot be null");
  }

  /**
   * The shard.
   *
   * @return shard id
   */
  public ShardId getShard() {
    return shard;
  }

  /**
   * The client of the shard's aggregator.
   *
   * @return client
   */
  public AggregatorClient getClient() {
    return client;
  }
}
