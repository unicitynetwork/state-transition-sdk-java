package org.unicitylabs.sdk.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inclusion proof request.
 */
public class InclusionProofRequest {

  private final StateId stateId;

  /**
   * Create inclusion proof request.
   *
   * @param stateId state id
   */
  @JsonCreator
  public InclusionProofRequest(
      @JsonProperty("stateId") StateId stateId
  ) {
    this.stateId = stateId;
  }

  /**
   * Get state id.
   *
   * @return state id
   */
  public StateId getStateId() {
    return this.stateId;
  }
}
