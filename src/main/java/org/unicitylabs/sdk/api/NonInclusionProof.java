package org.unicitylabs.sdk.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Unverified material that claims the absence of a state at a snapshot. It is not evidence until a
 * non-inclusion verification accepts it.
 *
 * <p>This is the transport-level holder of the shared interfaces. {@link #getEncoded()} is the
 * <em>complete</em> {@code get_non_inclusion_proof.v1} result exactly as the service sent it
 * (hex-decoded), the wrapper {@code [blockNumber, 39034([1, certificate, unicityCertificate])]}
 * included: wrapper-level malformation is a rejection of the verification, so the holder never
 * splits or normalizes the wrapper. The canonical decoding, the certificate model and the
 * verification belong to the non-inclusion verifier. An HTTP adapter passes the decoded result
 * bytes unchanged.
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
   * The complete {@code get_non_inclusion_proof.v1} result as received.
   *
   * @return copy of the bytes
   */
  public byte[] getEncoded() {
    return Arrays.copyOf(encoded, encoded.length);
  }
}
