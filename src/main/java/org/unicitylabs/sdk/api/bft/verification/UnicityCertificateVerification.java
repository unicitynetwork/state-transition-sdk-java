package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
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
    ArrayList<VerificationResult<?>> results = new ArrayList<>();
    VerificationResult<VerificationStatus> result = UnicitySealHashMatchesWithRootHashRule.verify(inclusionProof.getUnicityCertificate());
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(results);
    }

    result = UnicitySealQuorumSignaturesVerificationRule.verify(trustBase,
            inclusionProof.getUnicityCertificate()
                    .getUnicitySeal());
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(results);
    }

    return UnicityCertificateVerificationResult.ok(results);
  }

}
