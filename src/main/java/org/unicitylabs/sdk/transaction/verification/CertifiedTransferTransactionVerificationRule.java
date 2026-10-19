package org.unicitylabs.sdk.transaction.verification;

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
   * @param transaction certified transfer transaction to verify
   * @param context shared verification context (trust base + registries)
   *
   * @return verification result with child results for each validation step
   */
  public static VerificationResult<VerificationStatus> verify(
          CertifiedTransferTransaction transaction,
          VerificationContext context) {
    ArrayList<VerificationResult<?>> results = new ArrayList<VerificationResult<?>>();

    VerificationResult<?> result = InclusionProofVerificationRule.verify(context.getTrustBase(),
            context.getPredicateVerifier(), context.getCertificateVerifier(),
            transaction.getInclusionProof(), transaction);
    results.add(result);
    if (result.getStatus() != InclusionProofVerificationStatus.OK) {
      return new VerificationResult<>("CertifiedTransferTransactionVerificationRule",
              VerificationStatus.FAIL, "Inclusion proof verification failed", results);
    }

    return new VerificationResult<>("CertifiedTransferTransactionVerificationRule",
            VerificationStatus.OK, "", results);
  }
}
