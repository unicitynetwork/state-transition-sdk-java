package org.unicitylabs.sdk.predicate;

import java.util.Arrays;

public interface Predicate {

  PredicateEngine getEngine();

  byte[] encodeCode();

  byte[] encodeParameters();

  default boolean isEqualTo(Predicate other) {
    if (other == null) {
      return false;
    }

    return this.getEngine() == other.getEngine()
        && Arrays.equals(this.encodeCode(), other.encodeCode())
        && Arrays.equals(this.encodeParameters(), other.encodeParameters());
  }
}
