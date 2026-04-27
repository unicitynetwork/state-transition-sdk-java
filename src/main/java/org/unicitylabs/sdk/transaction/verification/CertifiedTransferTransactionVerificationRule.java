package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Address;
import org.unicitylabs.sdk.transaction.CertifiedTransferTransaction;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.ArrayList;

/**
 * Verification rule set for certified transfer transactions.
 *
 * <p>The verification checks inclusion proof validity, validates that the current transaction
 * is spent by previous recipient and ensures source-state-hash continuity.
 */
public class CertifiedTransferTransactionVerificationRule {

  private CertifiedTransferTransactionVerificationRule() {
  }

  /**
   * Verify a certified transfer transaction against the previous transaction.
   *
   * @param trustBase root trust base used for inclusion proof verification
   * @param predicateVerifier predicate verifier used by inclusion proof verification
   * @param latestTransaction latest transaction in token history
   * @param transaction certified transfer transaction to verify
   *
   * @return verification result with child results for each validation step
   */
  public static VerificationResult<VerificationStatus> verify(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          Transaction latestTransaction,
          CertifiedTransferTransaction transaction) {
    ArrayList<VerificationResult<?>> results = new ArrayList<VerificationResult<?>>();

    VerificationResult<?> result = InclusionProofVerificationRule.verify(trustBase,
            predicateVerifier, transaction.getInclusionProof(), transaction);
    results.add(result);
    if (result.getStatus() != InclusionProofVerificationStatus.OK) {
      return new VerificationResult<>("CertifiedTransferTransactionVerificationRule",
              VerificationStatus.FAIL, "Inclusion proof verification failed", results);
    }

    Address payToScriptHash = Address.fromPredicate(transaction.getLockScript());
    result = new VerificationResult<>("RecipientVerificationRule",
            latestTransaction.getRecipient().equals(payToScriptHash) ? VerificationStatus.OK
                    : VerificationStatus.FAIL);
    results.add(result);

    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("CertifiedTransferTransactionVerificationRule",
              VerificationStatus.FAIL,
              "Transaction owner does not match the previous transaction recipient", results);
    }

    result = new VerificationResult<>("SourceStateHashVerificationRule",
            latestTransaction.calculateStateHash().equals(transaction.getSourceStateHash())
                    ? VerificationStatus.OK : VerificationStatus.FAIL);
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("CertifiedTransferTransactionVerificationRule",
              VerificationStatus.FAIL,
              "Source state hash does not match the previous transaction state hash", results);
    }

    return new VerificationResult<>("CertifiedTransferTransactionVerificationRule",
            VerificationStatus.OK, "", results);
  }
}
