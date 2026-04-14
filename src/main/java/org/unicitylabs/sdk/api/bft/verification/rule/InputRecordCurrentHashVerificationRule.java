package org.unicitylabs.sdk.api.bft.verification.rule;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Input record current hash verification rule.
 */
public class InputRecordCurrentHashVerificationRule {

  private InputRecordCurrentHashVerificationRule() {
  }

  /**
   * Verify that inclusion proof merkle root matches current hash in input record.
   *
   * @param inclusionProof inclusion proof to verify
   *
   * @return verification result
   */
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
