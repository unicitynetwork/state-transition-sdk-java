package org.unicitylabs.sdk.predicate.builtin;

import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.Arrays;
import java.util.Objects;

/**
 * Built-in predicate that locks an output to a secp256k1 public key.
 */
public class SignaturePredicate implements BuiltInPredicate {

  private final byte[] publicKey;

  private SignaturePredicate(byte[] publicKey) {
    this.publicKey = publicKey;
  }

  /**
   * Get public key bytes.
   *
   * @return public key bytes
   */
  public byte[] getPublicKey() {
    return Arrays.copyOf(this.publicKey, this.publicKey.length);
  }

  /**
   * Get built-in predicate type.
   *
   * @return predicate type
   */
  public BuiltInPredicateType getType() {
    return BuiltInPredicateType.SIGNATURE;
  }

  /**
   * Create predicate from public key bytes.
   *
   * @param publicKey public key bytes
   *
   * @return pay-to-public-key predicate
   */
  public static SignaturePredicate create(byte[] publicKey) {
    return new SignaturePredicate(Arrays.copyOf(publicKey, publicKey.length));
  }

  /**
   * Parse pay-to-public-key predicate from generic predicate.
   *
   * @param predicate generic predicate
   *
   * @return pay-to-public-key predicate
   */
  public static SignaturePredicate fromPredicate(EncodedPredicate predicate) {
    PredicateEngine engine = predicate.getEngine();
    if (engine != PredicateEngine.BUILT_IN) {
      throw new IllegalArgumentException("Predicate engine must be BUILT_IN.");
    }

    BuiltInPredicateType type = BuiltInPredicateType.fromId(
            CborDeserializer.decodeUnsignedInteger(predicate.encodeCode()).asInt());
    if (type != BuiltInPredicateType.SIGNATURE) {
      throw new IllegalArgumentException("Predicate type must be SIGNATURE.");
    }

    return new SignaturePredicate(predicate.encodeParameters());
  }

  /**
   * Create predicate from signing service public key.
   *
   * @param signingService signing service
   *
   * @return pay-to-public-key predicate
   */
  public static SignaturePredicate fromSigningService(SigningService signingService) {
    Objects.requireNonNull(signingService, "Signing service cannot be null");
    return new SignaturePredicate(signingService.getPublicKey());
  }

  /**
   * Encode predicate parameters.
   *
   * @return encoded parameter bytes
   */
  @Override
  public byte[] encodeParameters() {
    return this.getPublicKey();
  }

  @Override
  public String toString() {
    return String.format("SignaturePredicate{publicKey=%s}", HexConverter.encode(this.publicKey));
  }

}
