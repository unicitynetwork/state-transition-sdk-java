package org.unicitylabs.sdk.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Unverified material that claims the absence of a state at a snapshot. It is not evidence until a
 * non-inclusion verification accepts it.
 *
 * <p>This is the transport-level holder agreed as a contract of the shared interfaces; package B
 * owns the canonical decoding, the certificate model and the verification, and extends this class
 * without changing the accessors below.
 */
public final class NonInclusionProof {

  private final StateId stateId;
  private final byte[] encoded;

  /**
   * Creates the holder.
   *
   * @param stateId the state the proof was requested for; bound locally, the wire does not carry it
   * @param encoded the proof bytes as the service sent them
   */
  public NonInclusionProof(StateId stateId, byte[] encoded) {
    this.stateId = Objects.requireNonNull(stateId, "stateId cannot be null");
    Objects.requireNonNull(encoded, "encoded cannot be null");
    this.encoded = Arrays.copyOf(encoded, encoded.length);
  }

  /**
   * The queried state.
   *
   * @return state id
   */
  public StateId getStateId() {
    return stateId;
  }

  /**
   * The proof bytes as received.
   *
   * @return copy of the bytes
   */
  public byte[] getEncoded() {
    return Arrays.copyOf(encoded, encoded.length);
  }
}
