package org.unicitylabs.sdk.predicate.builtin;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.predicate.builtin.verification.BuiltInPredicateVerifier;
import org.unicitylabs.sdk.predicate.builtin.verification.PayToPublicKeyPredicateVerifier;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifier;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link PredicateVerifier} implementation for built-in predicates.
 */
public class DefaultBuiltInPredicateVerifier implements PredicateVerifier {

  private final Map<BuiltInPredicateType, BuiltInPredicateVerifier> verifiers;


  /**
   * Creates a verifier registry from built-in predicate verifiers.
   *
   * @param verifiers verifiers to register, one per predicate type
   * @throws IllegalArgumentException if multiple verifiers are provided for the same type
   */
  public DefaultBuiltInPredicateVerifier(
          List<BuiltInPredicateVerifier> verifiers) {
    Map<BuiltInPredicateType, BuiltInPredicateVerifier> result = new HashMap<>();
    for (BuiltInPredicateVerifier verifier : verifiers) {
      if (result.containsKey(verifier.getType())) {
        throw new IllegalArgumentException("Duplicate verifier for type " + verifier.getType());
      }

      result.put(verifier.getType(), verifier);
    }

    this.verifiers = result;
  }

  @Override
  public PredicateEngine getPredicateEngine() {
    return PredicateEngine.BUILT_IN;
  }

  /**
   * Creates the default built-in predicate verifier set.
   *
   * @param service predicate verifier service
   * @param trustBase root trust base
   * @return default built-in predicate verifier
   */
  public static DefaultBuiltInPredicateVerifier create(PredicateVerifierService service, RootTrustBase trustBase) {
    return new DefaultBuiltInPredicateVerifier(
            List.of(
                    new PayToPublicKeyPredicateVerifier()
            )
    );
  }

  @Override
  public VerificationResult<VerificationStatus> verify(Predicate predicate,
                                                       DataHash sourceStateHash,
                                                       DataHash transactionHash, byte[] unlockScript) {
    BuiltInPredicateType type = BuiltInPredicateType.fromId(
            CborDeserializer.decodeUnsignedInteger(predicate.encodeCode()).asInt());

    BuiltInPredicateVerifier verifier = this.verifiers.get(type);
    if (verifier == null) {
      throw new IllegalArgumentException("No verifier registered for predicate type: " + type);
    }

    return verifier.verify(predicate, sourceStateHash, transactionHash, unlockScript);
  }
}
