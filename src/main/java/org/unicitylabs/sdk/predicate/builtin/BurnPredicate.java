package org.unicitylabs.sdk.predicate.builtin;

import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;

import java.util.Arrays;
import java.util.Objects;

/**
 * Built-in predicate representing a burn operation.
 */
public class BurnPredicate implements BuiltInPredicate {
  private final byte[] reason;

  private BurnPredicate(byte[] reason) {
    this.reason = Arrays.copyOf(reason, reason.length);
  }

  /**
   * Returns the built-in predicate type.
   *
   * @return {@link BuiltInPredicateType#BURN}
   */
  public BuiltInPredicateType getType() {
    return BuiltInPredicateType.BURN;
  }

  /**
   * Returns the burn reason bytes.
   *
   * @return a defensive copy of the burn reason
   */
  public byte[] getReason() {
    return Arrays.copyOf(this.reason, this.reason.length);
  }

  /**
   * Creates a burn predicate from the provided reason bytes.
   *
   * @param reason burn reason bytes
   * @return created burn predicate
   * @throws NullPointerException if {@code reason} is {@code null}
   */
  public static BurnPredicate create(byte[] reason) {
    Objects.requireNonNull(reason, "Reason cannot be null");

    return new BurnPredicate(reason);
  }

  /**
   * Converts a generic predicate into a {@link BurnPredicate}.
   *
   * @param predicate predicate to convert
   * @return converted burn predicate
   * @throws IllegalArgumentException if the predicate engine is not built-in or predicate type is not burn
   */
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

  /**
   * Encodes burn predicate parameters.
   *
   * @return burn reason bytes
   */
  @Override
  public byte[] encodeParameters() {
    return this.getReason();
  }
}
