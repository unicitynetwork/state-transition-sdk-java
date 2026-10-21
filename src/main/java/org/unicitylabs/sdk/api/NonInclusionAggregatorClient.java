package org.unicitylabs.sdk.api;

import java.util.concurrent.CompletableFuture;

/**
 * Optional capability of an aggregator client: {@code get_non_inclusion_proof.v1}.
 *
 * <p>Separate from {@link AggregatorClient}, which is unchanged: custom transports and test doubles
 * need not implement it. One call, no polling and no retry. The proof is bound to the state id
 * locally and is served from the latest certified snapshot of the service.
 *
 * <p>Non-inclusion proves absence only at the authenticated snapshot returned by verification. It
 * does not prove absence now, continuous absence, or that a token can be spent. Compare the
 * snapshot's network and root round (or its certified seal timestamp under your application's time
 * policy) with an independently trusted requirement. The seal timestamp is a consensus-certified
 * time, not proof of real-time freshness. Do not use unverified response metadata for this check.
 */
public interface NonInclusionAggregatorClient extends AggregatorClient {

  /**
   * Gets the non-inclusion proof of a state.
   *
   * <p>The future completes exceptionally with a typed unchecked error for service, transport and
   * response failures, deadline expiry and cancellation; cancelling the future cancels the call.
   *
   * @param stateId the state asked about
   * @param options optional operation; a wait is not allowed for this call
   * @return the unverified proof, or the service's claim that the state is included
   */
  CompletableFuture<NonInclusionProofResponse> getNonInclusionProof(StateId stateId,
      RequestOptions options);
}
