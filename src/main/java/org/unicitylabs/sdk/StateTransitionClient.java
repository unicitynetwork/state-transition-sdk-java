
package org.unicitylabs.sdk;

import java.util.concurrent.CompletableFuture;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.StateId;

/**
 * Client for handling state transitions of tokens, including submitting commitments and finalizing transactions.
 */
public class StateTransitionClient {

  /**
   * The aggregator client used for submitting commitments and retrieving inclusion proofs.
   */
  protected final AggregatorClient client;

  /**
   * Creates a new StateTransitionClient with the specified aggregator client.
   *
   * @param client The aggregator client to use for communication.
   */
  public StateTransitionClient(AggregatorClient client) {
    this.client = client;
  }

  /**
   * Retrieves the inclusion proof for a given transaction.
   *
   * @param stateId The state ID of inclusion proof to retrieve.
   * @return inclusion proof response from the aggregator.
   */
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
    return this.client.getInclusionProof(stateId);
  }

  public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData certificationData) {
    return this.client.submitCertificationRequest(certificationData);
  }


}
