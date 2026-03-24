package org.unicitylabs.sdk.api.bft.verification;

import java.util.ArrayList;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.verification.rule.InputRecordCurrentHashVerificationRule;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealHashMatchesWithRootHashRule;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealQuorumSignaturesVerificationRule;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class UnicityCertificateVerification {

  public static UnicityCertificateVerificationResult verify(RootTrustBase trustBase,
      InclusionProof inclusionProof) {
    ArrayList<VerificationResult<?>> results = new ArrayList<VerificationResult<?>>();
    VerificationResult<VerificationStatus> result = InputRecordCurrentHashVerificationRule.verify(inclusionProof);
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(results);
    }

    result = UnicitySealHashMatchesWithRootHashRule.verify(inclusionProof.getUnicityCertificate());
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
