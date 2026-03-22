package org.unicitylabs.sdk.predicate.verification;

import java.util.HashMap;
import java.util.Map;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.predicate.builtin.BuiltInPredicateVerifier;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class PredicateVerifierService {

  private final Map<PredicateEngine, PredicateVerifier> verifiers = new HashMap<>();

  private PredicateVerifierService() {

  }

  public static PredicateVerifierService create(RootTrustBase trustBase) {
    var verifier = new PredicateVerifierService();
    verifier.addVerifier(BuiltInPredicateVerifier.create(verifier, trustBase));

    return verifier;
  }

  public PredicateVerifierService addVerifier(PredicateVerifier verifier) {
    if (this.verifiers.containsKey(verifier.getPredicateEngine())) {
      throw new RuntimeException("Predicate verifier already registered for predicate engine: "
          + verifier.getPredicateEngine());
    }

    this.verifiers.put(verifier.getPredicateEngine(), verifier);

    return this;
  }

  public VerificationResult<VerificationStatus> verify(Predicate predicate,
      DataHash sourceStateHash, DataHash transactionHash, byte[] unlockScript) {
    var verifier = this.verifiers.get(predicate.getEngine());
    if (verifier == null) {
      throw new IllegalArgumentException(
          "No verifier registered for predicate engine: " + predicate.getEngine());
    }

    return verifier.verify(predicate, sourceStateHash, transactionHash, unlockScript);
  }
}
