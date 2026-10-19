package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;

import java.util.Objects;

/**
 * Verifies the unicity certificate of an inclusion proof: network, fixed partition, certificate
 * body and seal.
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
   * Verifies a unicity certificate on its own, for evidence that is not an inclusion proof (for
   * example a non-inclusion proof): no {@code InclusionProof} is fabricated around it. The plain
   * verifier applies the legacy quorum policy like {@link #verify}; an operation verifier applies
   * the policy, the seal session and the pins of its operation, so strict evidence shares the
   * work of the rest of the operation.
   *
   * @param trustBase the authority
   * @param certificate the certificate to verify
   * @return verification result
   */
  public UnicityCertificateVerificationResult verifyCertificate(RootTrustBase trustBase,
      UnicityCertificate certificate) {
    return UnicityCertificateVerification.verify(trustBase, certificate);
  }

  /**
   * Starts an operation: one verifier that spans the token and every nested token of one
   * verification, with one seal session per authority, a bounded body memo and counters.
   *
   * @param policy policy of the operation
   * @param pins optional trusted shard/configuration pins; null for none. Pins come from the
   *     caller's configuration, never from a response that carries the evidence
   * @return the verifier to use for the operation: an {@link OperationUnicityCertificateVerifier}
   *     for the plain verifier; a verifier customized by subclassing is not scoped and returns
   *     itself, keeping full control of the checks
   */
  public UnicityCertificateVerifier beginOperation(VerificationPolicy policy,
      PartitionViewPolicy pins) {
    Objects.requireNonNull(policy, "policy cannot be null");
    if (getClass() != UnicityCertificateVerifier.class) {
      return this;
    }
    return OperationUnicityCertificateVerifier.start(policy,
        pins == null ? PartitionViewPolicy.none() : pins);
  }
}
