package org.unicitylabs.sdk.api.bft.verification;

import java.util.Objects;
import java.util.function.Function;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;

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
   * Verifies the certificate of an inclusion proof: network, partition, body and the seal under the
   * trust-base entry of its own epoch.
   *
   * @param trustBase the trust-base history
   * @param inclusionProof the proof
   * @return verification result
   */
  public UnicityCertificateVerificationResult verify(TrustBaseHistoryView trustBase,
      InclusionProof inclusionProof) {
    return UnicityCertificateVerification.verify(trustBase, inclusionProof);
  }

  /**
   * Verifies a unicity certificate on its own, for evidence that is not an inclusion proof (for
   * example a non-inclusion proof): no {@code InclusionProof} is fabricated around it. The plain
   * verifier checks like {@link #verify}; an operation verifier applies the seal session and the
   * pins of its operation, so evidence shares the work of the rest of the operation.
   *
   * @param trustBase the trust-base history
   * @param certificate the certificate to verify
   * @return verification result
   */
  public UnicityCertificateVerificationResult verifyCertificate(TrustBaseHistoryView trustBase,
      UnicityCertificate certificate) {
    return UnicityCertificateVerification.verify(trustBase, certificate);
  }

  /**
   * Starts an operation: one verifier that spans the token and every nested token of one
   * verification, with one seal session per authority, a bounded body memo and counters.
   *
   * @param pins optional trusted shard/configuration pins; null for none. Pins come from the
   *     caller's configuration, never from a response that carries the evidence
   * @return the verifier to use for the operation: an {@link OperationUnicityCertificateVerifier}
   *     for the plain verifier; a verifier customized by subclassing is not scoped and returns
   *     itself, keeping full control of the checks
   */
  public UnicityCertificateVerifier beginOperation(PartitionViewPolicy pins) {
    if (getClass() != UnicityCertificateVerifier.class) {
      return this;
    }
    return OperationUnicityCertificateVerifier.start(
        pins == null ? PartitionViewPolicy.none() : pins, SealVerificationSession::create);
  }

  /**
   * Starts an operation like {@link #beginOperation(PartitionViewPolicy)} with a caller-chosen
   * seal session per trust-base view, to size the memo and the in-flight bound of the operation or
   * to supply an own quorum check ({@link SealQuorumRule}).
   *
   * @param pins optional trusted shard/configuration pins; null for none
   * @param sessions builds the seal session of one view; called once per distinct view object
   * @return the operation verifier
   */
  public UnicityCertificateVerifier beginOperation(PartitionViewPolicy pins,
      Function<TrustBaseHistoryView, SealVerificationSession> sessions) {
    Objects.requireNonNull(sessions, "sessions cannot be null");
    return OperationUnicityCertificateVerifier.start(
        pins == null ? PartitionViewPolicy.none() : pins, sessions);
  }
}
