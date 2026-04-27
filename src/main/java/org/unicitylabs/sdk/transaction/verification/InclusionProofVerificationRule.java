package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerification;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class InclusionProofVerificationRule {

  public static VerificationResult<InclusionProofVerificationStatus> verify(RootTrustBase trustBase,
                                                                            PredicateVerifierService predicateVerifier, InclusionProof inclusionProof,
                                                                            Transaction transaction) {
    if (inclusionProof.getInclusionCertificate() == null) {
      return new VerificationResult<>(
              "InclusionProofVerificationRule",
              InclusionProofVerificationStatus.INCLUSION_CERTIFICATE_MISSING
      );
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

    StateId stateId = StateId.fromTransaction(transaction);
    if (!inclusionProof.getInclusionCertificate().verify(stateId, certificationData.getTransactionHash(), new DataHash(HashAlgorithm.SHA256, inclusionProof.getUnicityCertificate().getInputRecord().getHash()))) {
      return new VerificationResult<>("InclusionProofVerificationRule",
              InclusionProofVerificationStatus.PATH_INVALID);
    }


    VerificationResult<?> result = UnicityCertificateVerification.verify(trustBase, inclusionProof);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>(
              "InclusionProofVerificationRule",
              InclusionProofVerificationStatus.INVALID_TRUSTBASE,
              "",
              result
      );
    }

    result = predicateVerifier.verify(
            transaction.getLockScript(),
            transaction.getSourceStateHash(),
            certificationData.getTransactionHash(),
            certificationData.getUnlockScript()
    );

    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("InclusionProofVerificationRule",
              InclusionProofVerificationStatus.NOT_AUTHENTICATED, "", result);
    }

    return new VerificationResult<>("InclusionProofVerificationRule",
            InclusionProofVerificationStatus.OK);
  }
}
