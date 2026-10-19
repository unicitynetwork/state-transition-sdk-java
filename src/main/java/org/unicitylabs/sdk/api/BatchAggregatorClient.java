package org.unicitylabs.sdk.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Optional capability of an aggregator client: batch inclusion proofs bound to one finalized local
 * view.
 *
 * <p>Separate from {@link AggregatorClient}, which is unchanged: custom transports and test doubles
 * need not implement it. A client without it cannot be used for consolidation, and single proof
 * calls are never emulated as a batch.
 */
public interface BatchAggregatorClient extends AggregatorClient {

  /** Most state ids of one batch call. */
  int BATCH_MAX_STATE_IDS = 256;

  /**
   * Gets the proofs of 1..{@link #BATCH_MAX_STATE_IDS} distinct state ids, all routed to this shard,
   * answered atomically from one finalized view or not at all, in request order.
   *
   * <p>The future completes exceptionally with a typed unchecked error for service, transport and
   * response failures, deadline expiry and cancellation. Cancelling the returned future cancels the
   * in-flight call.
   *
   * @param stateIds state ids to prove
   * @param options exact root or latest view, optional wait, optional operation
   * @return proofs in request order, plus the partition view for a latest request
   * @throws IllegalArgumentException if the list is empty, over the limit or holds a duplicate
   */
  CompletableFuture<InclusionProofBatch> getInclusionProofs(List<StateId> stateIds,
      BatchRequestOptions options);
}
