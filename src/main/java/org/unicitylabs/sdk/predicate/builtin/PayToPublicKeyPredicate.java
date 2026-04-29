package org.unicitylabs.sdk.predicate.builtin;

import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.Arrays;
import java.util.Objects;

/**
 * Built-in predicate that locks an output to a secp256k1 public key.
 */
public class PayToPublicKeyPredicate implements BuiltInPredicate {

  private final byte[] publicKey;

  private PayToPublicKeyPredicate(byte[] publicKey) {
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
    return BuiltInPredicateType.PAY_TO_PUBLIC_KEY;
  }

  /**
   * Create predicate from public key bytes.
   *
   * @param publicKey public key bytes
   *
   * @return pay-to-public-key predicate
   */
  public static PayToPublicKeyPredicate create(byte[] publicKey) {
    return new PayToPublicKeyPredicate(Arrays.copyOf(publicKey, publicKey.length));
  }

  /**
   * Parse pay-to-public-key predicate from generic predicate.
   *
   * @param predicate generic predicate
   *
   * @return pay-to-public-key predicate
   */
  public static PayToPublicKeyPredicate fromPredicate(Predicate predicate) {
    PredicateEngine engine = predicate.getEngine();
    if (engine != PredicateEngine.BUILT_IN) {
      throw new IllegalArgumentException("Predicate engine must be BUILT_IN.");
    }

    BuiltInPredicateType type = BuiltInPredicateType.fromId(
            CborDeserializer.decodeUnsignedInteger(predicate.encodeCode()).asInt());
    if (type != BuiltInPredicateType.PAY_TO_PUBLIC_KEY) {
      throw new IllegalArgumentException("Predicate type must be PAY_TO_PUBLIC_KEY.");
    }

    return new PayToPublicKeyPredicate(predicate.encodeParameters());
  }

  /**
   * Create predicate from signing service public key.
   *
   * @param signingService signing service
   *
   * @return pay-to-public-key predicate
   */
  public static PayToPublicKeyPredicate fromSigningService(SigningService signingService) {
    Objects.requireNonNull(signingService, "Signing service cannot be null");
    return new PayToPublicKeyPredicate(signingService.getPublicKey());
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
    return String.format("PayToPublicKeyPredicate{publicKey=%s}", HexConverter.encode(this.publicKey));
  }

}
