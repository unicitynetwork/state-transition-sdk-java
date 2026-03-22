package org.unicitylabs.sdk.predicate.builtin;

import org.unicitylabs.sdk.predicate.Predicate;

public interface BuiltInPredicate extends Predicate {

  BuiltInPredicateType getType();
}
