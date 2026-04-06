package org.unicitylabs.sdk.predicate.verification;

import java.util.HashMap;
import java.util.Map;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.predicate.builtin.DefaultBuiltInPredicateVerifier;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Service registry that routes predicate verification to engine-specific verifiers.
 */
public class PredicateVerifierService {

  private final Map<PredicateEngine, PredicateVerifier> verifiers = new HashMap<>();

  private PredicateVerifierService() {

  }

  /**
   * Creates a predicate verifier service with default verifier registrations.
   *
   * @param trustBase root trust base used by verifiers that require trust context
   * @return initialized predicate verifier service
   */
  public static PredicateVerifierService create(RootTrustBase trustBase) {
    PredicateVerifierService verifier = new PredicateVerifierService();
    verifier.addVerifier(DefaultBuiltInPredicateVerifier.create(verifier, trustBase));

    return verifier;
  }

  /**
   * Registers a predicate verifier for its predicate engine.
   *
   * @param verifier verifier to register
   * @return this service instance
   * @throws RuntimeException if a verifier is already registered for the same predicate engine
   */
  public PredicateVerifierService addVerifier(PredicateVerifier verifier) {
    if (this.verifiers.containsKey(verifier.getPredicateEngine())) {
      throw new RuntimeException("Predicate verifier already registered for predicate engine: "
          + verifier.getPredicateEngine());
    }

    this.verifiers.put(verifier.getPredicateEngine(), verifier);

    return this;
  }

  /**
   * Verifies a predicate by dispatching to a verifier registered for its engine.
   *
   * @param predicate predicate to verify
   * @param sourceStateHash hash of the source state
   * @param transactionHash hash of the transaction being verified
   * @param unlockScript unlock script bytes
   * @return verification result from the engine-specific verifier
   * @throws IllegalArgumentException if no verifier is registered for the predicate engine
   */
  public VerificationResult<VerificationStatus> verify(Predicate predicate,
      DataHash sourceStateHash, DataHash transactionHash, byte[] unlockScript) {
    PredicateVerifier verifier = this.verifiers.get(predicate.getEngine());
    if (verifier == null) {
      throw new IllegalArgumentException(
          "No verifier registered for predicate engine: " + predicate.getEngine());
    }

    return verifier.verify(predicate, sourceStateHash, transactionHash, unlockScript);
  }
}
