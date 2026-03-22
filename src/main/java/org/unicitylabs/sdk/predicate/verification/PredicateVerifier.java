package org.unicitylabs.sdk.predicate.verification;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public interface PredicateVerifier {

  PredicateEngine getPredicateEngine();

  VerificationResult<VerificationStatus> verify(Predicate predicate, DataHash sourceStateHash,
      DataHash transactionHash, byte[] unlockScript);
}
