package org.unicitylabs.sdk.api.bft.verification;

import java.util.ArrayList;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Verifies unicity certificate within an inclusion proof: network, fixed partition, certificate
 * body, seal structure, paths to the seal hash, and the seal under the trust-base entry of its own
 * epoch (see {@link SealVerificationSession}). There is no relaxed mode: a seal of an earlier
 * epoch verifies against the entry of that epoch, and against nothing else.
 */
public class UnicityCertificateVerification {

  private UnicityCertificateVerification() {
  }

  /**
   * Runs unicity certificate verification rules against the provided inclusion proof.
   *
   * @param trustBase trust-base history the seal is placed in
   * @param inclusionProof inclusion proof containing the certificate and seal
   * @return verification result aggregating rule outcomes
   */
  public static UnicityCertificateVerificationResult verify(TrustBaseHistoryView trustBase,
                                                            InclusionProof inclusionProof) {
    return verify(trustBase, inclusionProof.getUnicityCertificate());
  }

  /**
   * Runs unicity certificate verification rules against a certificate on its own, without an
   * inclusion proof around it (network, partition, body, seal hash, and the seal under the entry of
   * its epoch).
   *
   * @param trustBase trust-base history the seal is placed in
   * @param certificate unicity certificate with its seal
   * @return verification result aggregating rule outcomes
   */
  public static UnicityCertificateVerificationResult verify(TrustBaseHistoryView trustBase,
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

    SealVerificationResult seal = SealVerificationSession.create(trustBase)
        .authenticate(certificate.getUnicitySeal());
    results.add(seal);
    if (seal.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(seal.getFailure(), results);
    }
    return UnicityCertificateVerificationResult.ok(results);
  }
}
