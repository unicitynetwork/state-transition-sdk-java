package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.crypto.MintSigningService;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.transaction.CertifiedMintTransaction;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
   * @param transaction certified mint transaction to verify
   * @param context shared verification context (trust base + registries)
   * @param nestedTokenCollector collector receiving tokens embedded in the mint justification that
   *     the caller must verify
   *
   * @return verification result with child results for each validation step
   */
  public static VerificationResult<VerificationStatus> verify(
          CertifiedMintTransaction transaction,
          VerificationContext context,
          Consumer<Token> nestedTokenCollector
  ) {
    List<VerificationResult<?>> results = new ArrayList<>();

    if (!transaction.getNetworkId().equals(context.getTrustBase().getNetworkId())) {
      results.add(new VerificationResult<>("MintNetworkMatchesTrustBaseRule", VerificationStatus.FAIL));
      return new VerificationResult<>("CertifiedMintTransactionVerificationRule",
              VerificationStatus.FAIL, "Mint network does not match trust base.", results);
    }
    results.add(new VerificationResult<>("MintNetworkMatchesTrustBaseRule", VerificationStatus.OK));

    SigningService signingService = MintSigningService.create(transaction.getTokenId());
    EncodedPredicate expectedLockScript = EncodedPredicate.fromPredicate(SignaturePredicate.fromSigningService(signingService));
    VerificationResult<?> result = expectedLockScript
            .equals(
                    transaction.getInclusionProof().getCertificationData().getLockScript()
            )
            ? new VerificationResult<>("IsLockScriptValidVerificationRule", VerificationStatus.OK)
            : new VerificationResult<>("IsLockScriptValidVerificationRule", VerificationStatus.FAIL);

    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("CertifiedMintTransactionVerificationRule",
              VerificationStatus.FAIL, "Invalid lock script", results);
    }

    result = InclusionProofVerificationRule.verify(context.getTrustBase(),
            context.getPredicateVerifier(), context.getCertificateVerifier(),
            transaction.getInclusionProof(), transaction);
    results.add(result);
    if (result.getStatus() != InclusionProofVerificationStatus.OK) {
      return new VerificationResult<>("CertifiedMintTransactionVerificationRule",
              VerificationStatus.FAIL, "Inclusion proof verification failed", results);
    }

    result = context.getTokenIssuanceVerifier().verify(transaction);
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>(
              "CertifiedMintTransactionVerificationRule",
              VerificationStatus.FAIL,
              "Invalid token issuance",
              results
      );
    }

    result = context.getMintJustificationVerifier().verify(transaction, nestedTokenCollector);
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
