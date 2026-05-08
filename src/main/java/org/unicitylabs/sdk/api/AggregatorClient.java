
package org.unicitylabs.sdk.api;

import java.util.concurrent.CompletableFuture;

/**
 * Aggregator client structure.
 */
public interface AggregatorClient {

  /**
   * Submit certification request.
   *
   * @param certificationData certification data
   * @return certification response
   */
  CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData certificationData);

  /**
   * Get inclusion proof for state id.
   *
   * @param stateId state id
   * @return inclusion / non inclusion proof
   */
  CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId);

  /**
   * Get block height.
   *
   * @return block height
   */
  CompletableFuture<Long> getBlockHeight();
}
