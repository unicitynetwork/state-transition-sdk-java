package org.unicitylabs.sdk.api.bft.verification.rule;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Input record current hash verification rule.
 */
public class InputRecordCurrentHashVerificationRule {

  public static VerificationResult<VerificationStatus> verify(InclusionProof inclusionProof) {
    if (inclusionProof.getMerkleTreePath().getRootHash().equals(
        DataHash.fromImprint(inclusionProof.getUnicityCertificate().getInputRecord().getHash()))) {
      return new VerificationResult<>("InputRecordCurrentHashVerificationRule",
          VerificationStatus.OK);
    }

    return new VerificationResult<>("InputRecordCurrentHashVerificationRule",
        VerificationStatus.FAIL);
  }

}
