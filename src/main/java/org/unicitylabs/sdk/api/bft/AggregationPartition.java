package org.unicitylabs.sdk.api.bft;

/**
 * The aggregation partition of the network.
 *
 * <p>The SDK accepts application proofs of exactly this partition. There is no environment,
 * network-map or constructor override. The generic certificate and view codecs stay partition
 * agnostic: parsing a certificate of another partition succeeds, but no application verification
 * path (token genesis, transfers, nested source tokens, consolidation, non-inclusion) accepts it.
 */
public final class AggregationPartition {

  /** Partition identifier of the aggregation layer. */
  public static final long AGGREGATION_PARTITION_ID = 7L;

  private AggregationPartition() {
  }
}
