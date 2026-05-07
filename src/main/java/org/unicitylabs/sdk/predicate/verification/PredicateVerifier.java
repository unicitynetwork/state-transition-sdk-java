package org.unicitylabs.sdk.predicate.verification;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Verifier contract for predicates handled by a specific predicate engine.
 */
public interface PredicateVerifier {

  /**
   * Returns the predicate engine supported by this verifier.
   *
   * @return supported predicate engine
   */
  PredicateEngine getPredicateEngine();

  /**
   * Verifies a predicate in the context of a source state, transaction, and unlock script.
   *
   * @param predicate predicate to verify
   * @param sourceStateHash hash of the source state
   * @param transactionHash hash of the transaction being validated
   * @param unlockScript unlock script bytes
   * @return verification result with status and diagnostics
   */
  VerificationResult<VerificationStatus> verify(EncodedPredicate predicate, DataHash sourceStateHash,
                                                DataHash transactionHash, byte[] unlockScript);
}
