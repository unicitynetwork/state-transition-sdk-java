package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.RootTrustBase;

/**
 * Verifies the unicity certificate of an inclusion proof: network, certificate body and seal.
 *
 * <p>The plain verifier is stateless and delegates to the existing static
 * {@link UnicityCertificateVerification}, so existing callers keep their behavior. An operation
 * verifier (see {@link #beginOperation}) additionally shares work between the proofs of one
 * operation, including the proofs of nested tokens.
 */
public class UnicityCertificateVerifier {

  /** Creates the plain verifier. */
  public UnicityCertificateVerifier() {
  }

  /**
   * Verifies the certificate of an inclusion proof under the legacy quorum policy.
   *
   * @param trustBase the authority
   * @param inclusionProof the proof
   * @return verification result
   */
  public UnicityCertificateVerificationResult verify(RootTrustBase trustBase,
      InclusionProof inclusionProof) {
    return UnicityCertificateVerification.verify(trustBase, inclusionProof);
  }

  /**
   * Starts an operation: one verifier that spans the token and every nested token of one
   * verification, with one seal session per authority, a bounded body memo and counters.
   *
   * @param policy policy of the operation
   * @param pins optional trusted shard/configuration pins; null for none. Pins come from the
   *     caller's configuration, never from a response that carries the evidence
   * @return operation verifier; verifiers customized by subclassing are not scoped and return
   *     themselves
   */
  public OperationUnicityCertificateVerifier beginOperation(VerificationPolicy policy,
      PartitionViewPolicy pins) {
    throw new UnsupportedOperationException("Implemented by package A");
  }
}
