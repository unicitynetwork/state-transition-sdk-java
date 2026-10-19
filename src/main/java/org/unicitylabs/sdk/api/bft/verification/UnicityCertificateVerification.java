package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealHashMatchesWithRootHashRule;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealQuorumSignaturesVerificationRule;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;


import java.util.ArrayList;

/**
 * Verifies unicity certificate within an inclusion proof.
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
   * inclusion proof around it (legacy quorum policy: network, seal hash and quorum).
   *
   * @param trustBase trust base used for quorum signature verification
   * @param certificate unicity certificate with its seal
   * @return verification result aggregating rule outcomes
   */
  public static UnicityCertificateVerificationResult verify(RootTrustBase trustBase,
                                                            UnicityCertificate certificate) {
    ArrayList<VerificationResult<?>> results = new ArrayList<>();

    if (!certificate.getUnicitySeal().getNetworkId().equals(trustBase.getNetworkId())) {
      results.add(new VerificationResult<>("UnicitySealNetworkMatchesTrustBaseRule", VerificationStatus.FAIL));
      return UnicityCertificateVerificationResult.fail(results);
    }
    results.add(new VerificationResult<>("UnicitySealNetworkMatchesTrustBaseRule", VerificationStatus.OK));

    VerificationResult<VerificationStatus> result = UnicitySealHashMatchesWithRootHashRule.verify(certificate);
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(results);
    }

    result = UnicitySealQuorumSignaturesVerificationRule.verify(trustBase,
            certificate.getUnicitySeal());
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(results);
    }

    return UnicityCertificateVerificationResult.ok(results);
  }

}
