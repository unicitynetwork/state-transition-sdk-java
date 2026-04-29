package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.unicityid.CertifiedUnicityIdMintTransaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Verification rule for the genesis (mint) of a unicity id token. Validates the inclusion proof of
 * the certified mint transaction.
 */
public class CertifiedUnicityIdMintTransactionVerificationRule {

  private CertifiedUnicityIdMintTransactionVerificationRule() {
  }

  /**
   * Verify the certified unicity id mint transaction.
   *
   * @param trustBase root trust base
   * @param predicateVerifier predicate verifier
   * @param genesis certified unicity id mint transaction to verify
   *
   * @return verification result
   */
  public static VerificationResult<VerificationStatus> verify(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          CertifiedUnicityIdMintTransaction genesis
  ) {
    List<VerificationResult<?>> results = new ArrayList<>();

    VerificationResult<InclusionProofVerificationStatus> result = InclusionProofVerificationRule.verify(
            trustBase,
            predicateVerifier,
            genesis.getInclusionProof(),
            genesis
    );
    results.add(result);
    if (result.getStatus() != InclusionProofVerificationStatus.OK) {
      return new VerificationResult<>(
              "CertifiedUnicityIdMintTransactionVerificationRule",
              VerificationStatus.FAIL,
              String.format("Inclusion proof verification failed: %s", result.getStatus()),
              results
      );
    }

    return new VerificationResult<>(
            "CertifiedUnicityIdMintTransactionVerificationRule",
            VerificationStatus.OK,
            "",
            results
    );
  }
}
