package org.unicitylabs.sdk.predicate;

import java.util.Arrays;

/**
 * Base contract for all predicate implementations.
 */
public interface Predicate {

  /**
   * Returns the predicate engine used by this predicate.
   *
   * @return the predicate engine
   */
  PredicateEngine getEngine();

  /**
   * Encodes the predicate type/code portion.
   *
   * @return encoded predicate code bytes
   */
  byte[] encodeCode();

  /**
   * Encodes the predicate parameter payload.
   *
   * @return encoded predicate parameter bytes
   */
  byte[] encodeParameters();

  /**
   * Compares this predicate with another predicate using encoded representation.
   *
   * @param other the predicate to compare against
   * @return {@code true} when engine, code, and parameters are equal; otherwise {@code false}
   */
  default boolean isEqualTo(Predicate other) {
    if (other == null) {
      return false;
    }

    return this.getEngine() == other.getEngine()
            && Arrays.equals(this.encodeCode(), other.encodeCode())
            && Arrays.equals(this.encodeParameters(), other.encodeParameters());
  }
}
