package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealQuorumSignaturesVerificationRule;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.ArrayList;

/**
 * Verifies unicity certificate within an inclusion proof under the legacy quorum policy: network,
 * fixed partition, certificate body, seal structure, paths to the seal hash and the quorum
 * signatures. The epoch of the seal is deliberately not constrained, historical token proofs may
 * carry seals of earlier epochs; strict authentication is {@link SealVerificationSession}.
 */
public class UnicityCertificateVerification {

  private UnicityCertificateVerification() {
  }

  /**
   * Runs unicity certificate verification rules against the provided inclusion proof.
   *
   * @param trustBase trust base used for quorum signature verification
   * @param inclusionProof inclusion proof containing the certificate and seal
   * @return verification result aggregating rule outcomes
   */
  public static UnicityCertificateVerificationResult verify(RootTrustBase trustBase,
                                                            InclusionProof inclusionProof) {
    return verify(trustBase, inclusionProof.getUnicityCertificate());
  }

  /**
   * Runs unicity certificate verification rules against a certificate on its own, without an
   * inclusion proof around it (legacy quorum policy: network, partition, body, seal hash and
   * quorum).
   *
   * @param trustBase trust base used for quorum signature verification
   * @param certificate unicity certificate with its seal
   * @return verification result aggregating rule outcomes
   */
  public static UnicityCertificateVerificationResult verify(RootTrustBase trustBase,
                                                            UnicityCertificate certificate) {
    ArrayList<VerificationResult<?>> results = new ArrayList<>();

    VerificationResult<VerificationStatus> network = CertificateChecks.network(trustBase,
        certificate);
    results.add(network);
    if (network.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(
          UnicityCertificateFailure.NETWORK_MISMATCH, results);
    }

    VerificationResult<VerificationStatus> partition = CertificateChecks.partition(certificate);
    results.add(partition);
    if (partition.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(
          UnicityCertificateFailure.PARTITION_MISMATCH, results);
    }

    CertificateChecks.Body body = CertificateChecks.body(certificate);
    results.addAll(body.getRules());
    if (!body.isOk()) {
      return UnicityCertificateVerificationResult.fail(body.getFailure(), results);
    }

    VerificationResult<VerificationStatus> quorum = UnicitySealQuorumSignaturesVerificationRule
        .verify(trustBase, certificate.getUnicitySeal());
    results.add(quorum);
    if (quorum.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(
          UnicityCertificateFailure.QUORUM_NOT_REACHED, results);
    }

    return UnicityCertificateVerificationResult.ok(results);
  }

}
