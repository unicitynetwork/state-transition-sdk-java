package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.MintSigningService;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.CertifiedMintTransaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Verification rule set for certified mint transactions.
 *
 * <p>The verification checks that the lock script in certification data matches the expected
 * mint lock script derived from the token id, and that the inclusion proof is valid.
 */
public class CertifiedMintTransactionVerificationRule {

  private CertifiedMintTransactionVerificationRule() {
  }

  /**
   * Verify a certified mint transaction.
   *
   * @param trustBase root trust base
   * @param predicateVerifier predicate verifier
   * @param mintJustificationVerifier mint justification verifier
   * @param transaction certified mint transaction to verify
   *
   * @return verification result with child results for each validation step
   */
  public static VerificationResult<VerificationStatus> verify(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          MintJustificationVerifierService mintJustificationVerifier,
          CertifiedMintTransaction transaction
  ) {
    List<VerificationResult<?>> results = new ArrayList<>();

    SigningService signingService = MintSigningService.create(transaction.getTokenId());
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

    result = mintJustificationVerifier.verify(transaction);
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>(
              "CertifiedMintTransactionVerificationRule",
              VerificationStatus.FAIL,
              "Invalid mint justification",
              results
      );
    }

    return new VerificationResult<>("CertifiedMintTransactionVerificationRule",
            VerificationStatus.OK, "", results);
  }
}
