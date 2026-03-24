package org.unicitylabs.sdk.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;
import java.util.Objects;

/**
 * Inclusion proof request.
 */
public class InclusionProofRequest {

  private final byte[] stateId;

  /**
   * Create inclusion proof request.
   *
   * @param stateId state id
   */
  @JsonCreator
  public InclusionProofRequest(
      @JsonProperty("stateId") StateId stateId
  ) {
    Objects.requireNonNull(stateId, "stateId cannot be null");

    this.stateId = stateId.getData();
  }

  public byte[] getStateId() {
    return Arrays.copyOf(this.stateId, this.stateId.length);
  }
}
