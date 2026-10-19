package org.unicitylabs.sdk.api;

import java.util.Objects;

/**
 * What the service answered to a non-inclusion request: either material that claims absence, or an
 * explicitly unauthenticated hint that the state is included. Neither is evidence until verified.
 */
public final class NonInclusionProofResponse {

  /** Kind of the answer. */
  public enum Kind {
    /** An absence proof that has to be verified. */
    ABSENCE_PROOF,
    /** The service says the state is certified. Unauthenticated, not inclusion evidence. */
    STATE_INCLUDED_HINT
  }

  private final Kind kind;
  private final NonInclusionProof proof;

  private NonInclusionProofResponse(Kind kind, NonInclusionProof proof) {
    this.kind = kind;
    this.proof = proof;
  }

  /**
   * Creates an absence proof answer.
   *
   * @param proof unverified proof
   * @return response
   */
  public static NonInclusionProofResponse absence(NonInclusionProof proof) {
    return new NonInclusionProofResponse(Kind.ABSENCE_PROOF,
        Objects.requireNonNull(proof, "proof cannot be null"));
  }

  /**
   * Creates the included hint answer.
   *
   * @return response without proof
   */
  public static NonInclusionProofResponse includedHint() {
    return new NonInclusionProofResponse(Kind.STATE_INCLUDED_HINT, null);
  }

  /**
   * Kind of the answer.
   *
   * @return kind
   */
  public Kind getKind() {
    return kind;
  }

  /**
   * The unverified proof.
   *
   * @return proof, null for the included hint
   */
  public NonInclusionProof getProof() {
    return proof;
  }
}
