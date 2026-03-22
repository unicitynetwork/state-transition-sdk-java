package org.unicitylabs.sdk.predicate.builtin.verification;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.builtin.BuiltInPredicateType;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public interface BuiltInPredicateVerifier {

  BuiltInPredicateType getType();

  VerificationResult<VerificationStatus> verify(Predicate predicate, DataHash sourceStateHash,
      DataHash transactionHash, byte[] unlockScript);
}
