package org.unicitylabs.sdk.api;

import java.util.concurrent.CompletableFuture;

/**
 * Optional capability of an aggregator client: single calls with {@link RequestOptions}, that is
 * a wait (long poll), the status hint, a per-call timeout and the budget and cancellation of an
 * operation.
 *
 * <p>Separate from {@link AggregatorClient}, which is unchanged: custom transports and test doubles
 * need not implement it. A wait over a client without it polls at the configured interval, without
 * status hints.
 *
 * <p>Cancelling a returned future cancels the in-flight call. The future completes exceptionally
 * with a typed unchecked error for service and response failures, and with {@link
 * java.io.IOException}, {@link java.util.concurrent.TimeoutException} or {@link
 * java.util.concurrent.CancellationException} for transport, deadline and cancellation.
 */
public interface StatusAwareAggregatorClient extends AggregatorClient {

  /**
   * Gets the inclusion proof answer of a state.
   *
   * @param stateId state id
   * @param options wait, status hint, call timeout and operation
   * @return the answer, with the status hint and the wait echo when the aggregator sent them
   */
  CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId,
      RequestOptions options);

  /**
   * Submits a certification request.
   *
   * @param certificationData certification data
   * @param options operation and call timeout; a wait is not allowed for a submission
   * @return certification response
   */
  CompletableFuture<CertificationResponse> submitCertificationRequest(
      CertificationData certificationData, RequestOptions options);
}
