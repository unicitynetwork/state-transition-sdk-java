package org.unicitylabs.sdk.transaction.verification;

import java.util.ArrayList;
import java.util.Arrays;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.MintSigningService;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.CertifiedMintTransaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class CertifiedMintTransactionVerificationRule {

  public static VerificationResult<VerificationStatus> verify(RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier, CertifiedMintTransaction transaction) {
    var results = new ArrayList<VerificationResult<?>>();

    var signingService = MintSigningService.create(transaction.getTokenId());
    VerificationResult<?> result = Arrays.equals(
        EncodedPredicate.fromPredicate(PayToPublicKeyPredicate.fromSigningService(signingService))
            .toCbor(),
        transaction.getInclusionProof()
            .getCertificationData()
            .map(c -> EncodedPredicate.fromPredicate(c.getLockScript()).toCbor())
            .orElse(null))
        ? new VerificationResult<>("IsLockScriptValidVerificationRule", VerificationStatus.OK)
        : new VerificationResult<>("IsLockScriptValidVerificationRule", VerificationStatus.FAIL);

    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("CertifiedMintTransactionVerificationRule",
          VerificationStatus.FAIL, "Invalid lock script", results);
    }

    result = InclusionProofVerificationRule.verify(trustBase, predicateVerifier,
        transaction.getInclusionProof(), transaction);
    results.add(result);
    if (result.getStatus() != InclusionProofVerificationStatus.OK) {
      return new VerificationResult<>("CertifiedMintTransactionVerificationRule",
          VerificationStatus.FAIL, "Inclusion proof verification failed", results);
    }

    return new VerificationResult<>("CertifiedMintTransactionVerificationRule",
        VerificationStatus.OK, "", results);
  }
}
