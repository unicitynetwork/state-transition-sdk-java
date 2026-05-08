package org.unicitylabs.sdk.predicate.builtin.verification;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.builtin.BuiltInPredicateType;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Verifier contract for a specific built-in predicate type.
 */
public interface BuiltInPredicateVerifier {

  /**
   * Returns the built-in predicate type handled by this verifier.
   *
   * @return supported built-in predicate type
   */
  BuiltInPredicateType getType();

  /**
   * Verifies that the provided unlock script satisfies the predicate in the current context.
   *
   * @param predicate the predicate to verify
   * @param sourceStateHash hash of the source state
   * @param transactionHash hash of the transaction being validated
   * @param unlockScript unlock script bytes provided for the predicate
   * @return verification result with status and optional diagnostics
   */
  VerificationResult<VerificationStatus> verify(EncodedPredicate predicate, DataHash sourceStateHash,
                                                DataHash transactionHash, byte[] unlockScript);
}
