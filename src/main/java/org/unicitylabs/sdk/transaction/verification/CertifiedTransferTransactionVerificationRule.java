package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.CertifiedTransferTransaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.ArrayList;

/**
 * Verification rule set for certified transfer transactions.
 *
 * <p>The verification checks that the certified transfer transaction's inclusion proof is valid
 * against the trust base.
 */
public class CertifiedTransferTransactionVerificationRule {

  private CertifiedTransferTransactionVerificationRule() {
  }

  /**
   * Verify a certified transfer transaction against the previous transaction.
   *
   * @param trustBase root trust base used for inclusion proof verification
   * @param predicateVerifier predicate verifier used by inclusion proof verification
   * @param transaction certified transfer transaction to verify
   *
   * @return verification result with child results for each validation step
   */
  public static VerificationResult<VerificationStatus> verify(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          CertifiedTransferTransaction transaction) {
    ArrayList<VerificationResult<?>> results = new ArrayList<VerificationResult<?>>();

    VerificationResult<?> result = InclusionProofVerificationRule.verify(trustBase,
            predicateVerifier, transaction.getInclusionProof(), transaction);
    results.add(result);
    if (result.getStatus() != InclusionProofVerificationStatus.OK) {
      return new VerificationResult<>("CertifiedTransferTransactionVerificationRule",
              VerificationStatus.FAIL, "Inclusion proof verification failed", results);
    }

    return new VerificationResult<>("CertifiedTransferTransactionVerificationRule",
            VerificationStatus.OK, "", results);
  }
}
