package org.unicitylabs.sdk.predicate.builtin;

import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.PredicateEngine;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

public interface BuiltInPredicate extends Predicate {

  BuiltInPredicateType getType();

  default PredicateEngine getEngine() {
    return PredicateEngine.BUILT_IN;
  }

  default byte[] encodeCode() {
    return CborSerializer.encodeUnsignedInteger(this.getType().getId());
  }
}
