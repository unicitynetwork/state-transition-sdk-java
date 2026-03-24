package org.unicitylabs.sdk.predicate.builtin;

import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;

public class BurnPredicate implements BuiltInPredicate {
  private final byte[] reason;

  private BurnPredicate(byte[] reason) {
    this.reason = Arrays.copyOf(reason, reason.length);
  }

  public BuiltInPredicateType getType() {
    return BuiltInPredicateType.BURN;
  }

  public byte[] getReason() {
    return Arrays.copyOf(this.reason, this.reason.length);
  }

  public static BurnPredicate create(byte[] reason) {
    Objects.requireNonNull(reason, "Reason cannot be null");

    return new BurnPredicate(reason);
  }

  public static BurnPredicate fromPredicate(Predicate predicate) {
    PredicateEngine engine = predicate.getEngine();
    if (engine != PredicateEngine.BUILT_IN) {
      throw new IllegalArgumentException("Predicate engine must be BUILT_IN.");
    }

    BuiltInPredicateType type = BuiltInPredicateType.fromId(
        CborDeserializer.decodeUnsignedInteger(predicate.encodeCode()).asInt());
    if (type != BuiltInPredicateType.BURN) {
      throw new IllegalArgumentException("Predicate type must be BURN.");
    }

    return new BurnPredicate(predicate.encodeParameters());
  }

  @Override
  public byte[] encodeParameters() {
    return this.getReason();
  }
}
