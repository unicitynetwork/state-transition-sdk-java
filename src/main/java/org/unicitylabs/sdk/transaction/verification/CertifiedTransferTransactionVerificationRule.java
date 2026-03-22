package org.unicitylabs.sdk.transaction.verification;

import java.util.ArrayList;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Address;
import org.unicitylabs.sdk.transaction.CertifiedTransferTransaction;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class CertifiedTransferTransactionVerificationRule {

  public static VerificationResult<VerificationStatus> verify(
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      Transaction latestTransaction,
      CertifiedTransferTransaction transaction) {
    var results = new ArrayList<VerificationResult<?>>();

    VerificationResult<?> result = InclusionProofVerificationRule.verify(trustBase,
        predicateVerifier, transaction.getInclusionProof(), transaction);
    results.add(result);
    if (result.getStatus() != InclusionProofVerificationStatus.OK) {
      return new VerificationResult<>("CertifiedTransferTransactionVerificationRule",
          VerificationStatus.FAIL, "Inclusion proof verification failed", results);
    }

    var payToScriptHash = Address.fromPredicate(transaction.getLockScript());
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
