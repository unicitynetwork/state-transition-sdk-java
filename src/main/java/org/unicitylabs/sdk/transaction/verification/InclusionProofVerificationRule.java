package org.unicitylabs.sdk.transaction.verification;

import java.util.Arrays;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerification;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.mtree.MerkleTreePathVerificationResult;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTreePathStep;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class InclusionProofVerificationRule {

  public static VerificationResult<InclusionProofVerificationStatus> verify(RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier, InclusionProof inclusionProof,
      Transaction transaction) {
    VerificationResult<?> result = UnicityCertificateVerification.verify(trustBase, inclusionProof);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("InclusionProofVerificationRule",
          InclusionProofVerificationStatus.INVALID_TRUSTBASE, "", result);
    }

    StateId stateId = StateId.fromTransaction(transaction);
    MerkleTreePathVerificationResult pathVerificationResult = inclusionProof.getMerkleTreePath()
        .verify(stateId.toBitString().toBigInteger());
    if (!pathVerificationResult.isPathValid()) {
      return new VerificationResult<>("InclusionProofVerificationRule",
          InclusionProofVerificationStatus.PATH_INVALID);
    }

    if (!pathVerificationResult.isPathIncluded()) {
      return new VerificationResult<>("InclusionProofVerificationRule",
          InclusionProofVerificationStatus.PATH_NOT_INCLUDED);
    }

    CertificationData certificationData = inclusionProof.getCertificationData().orElse(null);
    if (certificationData == null) {
      return new VerificationResult<>("InclusionProofVerificationRule",
          InclusionProofVerificationStatus.MISSING_CERTIFICATION_DATA);
    }

    if (!certificationData.getTransactionHash().equals(transaction.calculateTransactionHash())) {
      return new VerificationResult<>("InclusionProofVerificationRule",
          InclusionProofVerificationStatus.TRANSACTION_HASH_MISMATCH);
    }

    result = predicateVerifier.verify(certificationData.getLockScript(),
        certificationData.getSourceStateHash(), certificationData.getTransactionHash(),
        certificationData.getUnlockScript());

    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("InclusionProofVerificationRule",
          InclusionProofVerificationStatus.NOT_AUTHENTICATED, "", result);
    }

    DataHash leafValue = certificationData.calculateLeafValue();
    byte[] pathValue = inclusionProof.getMerkleTreePath().getSteps().stream().findFirst()
        .flatMap(SparseMerkleTreePathStep::getData).orElse(null);
    if (pathValue == null || !Arrays.equals(leafValue.getImprint(), pathValue)) {
      return new VerificationResult<>("InclusionProofVerificationRule",
          InclusionProofVerificationStatus.LEAF_VALUE_MISMATCH);
    }

    return new VerificationResult<>("InclusionProofVerificationRule",
        InclusionProofVerificationStatus.OK);
  }
}
