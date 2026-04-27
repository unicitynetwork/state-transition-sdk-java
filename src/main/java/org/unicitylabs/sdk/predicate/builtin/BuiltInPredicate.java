package org.unicitylabs.sdk.predicate.builtin;

import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/**
 * Base contract for predicates represented by a built-in predicate type.
 */
public interface BuiltInPredicate extends Predicate {

  /**
   * Returns the built-in type identifier for this predicate.
   *
   * @return the built-in predicate type
   */
  BuiltInPredicateType getType();

  /**
   * Returns the predicate engine used by all built-in predicates.
   *
   * @return {@link PredicateEngine#BUILT_IN}
   */
  default PredicateEngine getEngine() {
    return PredicateEngine.BUILT_IN;
  }

  /**
   * Encodes this predicate type id as an unsigned CBOR integer.
   *
   * @return the encoded predicate type id
   */
  default byte[] encodeCode() {
    return CborSerializer.encodeUnsignedInteger(this.getType().getId());
  }
}
