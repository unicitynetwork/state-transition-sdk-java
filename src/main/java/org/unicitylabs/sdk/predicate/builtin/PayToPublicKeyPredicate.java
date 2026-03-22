package org.unicitylabs.sdk.predicate.builtin;

import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

public class PayToPublicKeyPredicate implements BuiltInPredicate {

  private final byte[] publicKey;

  private PayToPublicKeyPredicate(byte[] publicKey) {
    this.publicKey = publicKey;
  }

  @Override
  public PredicateEngine getEngine() {
    return PredicateEngine.BUILT_IN;
  }

  public byte[] getPublicKey() {
    return Arrays.copyOf(this.publicKey, this.publicKey.length);
  }

  public BuiltInPredicateType getType() {
    return BuiltInPredicateType.PAY_TO_PUBLIC_KEY;
  }

  public static PayToPublicKeyPredicate create(byte[] publicKey) {
    return new PayToPublicKeyPredicate(Arrays.copyOf(publicKey, publicKey.length));
  }

  public static PayToPublicKeyPredicate fromPredicate(Predicate predicate) {
    var engine = predicate.getEngine();
    if (engine != PredicateEngine.BUILT_IN) {
      throw new IllegalArgumentException("Predicate engine must be BUILT_IN.");
    }

    var type = BuiltInPredicateType.fromId(
        CborDeserializer.decodeUnsignedInteger(predicate.encodeCode()).asInt());
    if (type != BuiltInPredicateType.PAY_TO_PUBLIC_KEY) {
      throw new IllegalArgumentException("Predicate type must be PAY_TO_PUBLIC_KEY.");
    }

    return new PayToPublicKeyPredicate(predicate.encodeParameters());
  }

  public static PayToPublicKeyPredicate fromSigningService(SigningService signingService) {
    Objects.requireNonNull(signingService, "Signing service cannot be null");
    return new PayToPublicKeyPredicate(signingService.getPublicKey());
  }

  @Override
  public byte[] encodeCode() {
    return CborSerializer.encodeUnsignedInteger(this.getType().getId());
  }

  @Override
  public byte[] encodeParameters() {
    return this.getPublicKey();
  }

}
