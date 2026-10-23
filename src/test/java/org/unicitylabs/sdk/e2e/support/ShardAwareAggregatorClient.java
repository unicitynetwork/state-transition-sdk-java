package org.unicitylabs.sdk.e2e.support;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.StateId;

/**
 * Test-only multi-shard {@link AggregatorClient} decorator that routes
 * requests to the correct shard aggregator based on the {@link StateId}.
 *
 * <p>Mirrors the TS-side {@code ShardAwareAggregatorClient} so the bft-shard
 * routing pin tests can exercise the exact same logic in Java.
 *
 * <p>Two routing modes are supported:
 * <ul>
 *   <li>{@code lsb} — least-significant bits of {@code stateId.data} starting
 *   at byte 0, bit-by-bit. Mirrors the Go aggregator's MatchesShardPrefix.</li>
 *   <li>{@code msb} — most-significant bits of byte 0 first, used by the
 *   bft-shard mode introduced in aggregator PR #146.</li>
 * </ul>
 */
public final class ShardAwareAggregatorClient implements AggregatorClient {

  public enum RoutingMode { LSB, MSB }

  private final RoutingMode routingMode;
  private final int shardIdLength;
  private final Map<Integer, AggregatorClient> shardMap;

  public ShardAwareAggregatorClient(
      int shardIdLength,
      Map<Integer, AggregatorClient> shardMap,
      RoutingMode routingMode) {
    this.shardIdLength = shardIdLength;
    this.shardMap = Objects.requireNonNull(shardMap, "shardMap");
    this.routingMode = Objects.requireNonNull(routingMode, "routingMode");

    int baseId = 1 << shardIdLength;
    int expectedCount = 1 << shardIdLength;
    for (int i = 0; i < expectedCount; i++) {
      int shardId = baseId + i;
      if (!shardMap.containsKey(shardId)) {
        throw new IllegalArgumentException(
            "Missing client for shard ID " + shardId + ". Expected all shard IDs from "
                + baseId + " to " + (baseId + expectedCount - 1));
      }
    }
  }

  public static int getShardForStateId(StateId stateId, int shardIdLength, RoutingMode routingMode) {
    if (shardIdLength == 0) {
      return 1;
    }

    byte[] data = stateId.getData();

    if (routingMode == RoutingMode.MSB) {
      // MSB mode: read top bits of byte 0 first, then byte 1, etc.
      int shardBits = 0;
      int consumed = 0;
      int byteIdx = 0;
      while (consumed < shardIdLength) {
        int remaining = shardIdLength - consumed;
        int take = Math.min(8, remaining);
        int top = (data[byteIdx] & 0xff) >>> (8 - take);
        shardBits = (shardBits << take) | top;
        consumed += take;
        byteIdx += 1;
      }
      return (1 << shardIdLength) | shardBits;
    }

    // LSB mode: bit-by-bit, LSB-first across bytes starting at byte 0.
    int shardBits = 0;
    for (int d = 0; d < shardIdLength; d++) {
      int bit = ((data[d >>> 3] & 0xff) >>> (d & 7)) & 1;
      shardBits |= bit << d;
    }
    return (1 << shardIdLength) | shardBits;
  }

  @Override
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
    int shardId = getShardForStateId(stateId, shardIdLength, routingMode);
    return shardMap.get(shardId).getInclusionProof(stateId);
  }

  @Override
  public CompletableFuture<CertificationResponse> submitCertificationRequest(
      CertificationData certificationData) {
    StateId stateId = StateId.fromCertificationData(certificationData);
    int shardId = getShardForStateId(stateId, shardIdLength, routingMode);
    return shardMap.get(shardId).submitCertificationRequest(certificationData);
  }

  @Override
  public CompletableFuture<Long> getBlockHeight() {
    // Pick any backend — block height is not shard-specific in tests.
    return shardMap.values().iterator().next().getBlockHeight();
  }
}
