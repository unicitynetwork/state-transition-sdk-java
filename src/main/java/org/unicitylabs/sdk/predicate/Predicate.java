package org.unicitylabs.sdk.predicate;

import java.util.Arrays;
import java.util.Objects;

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
   * Checks if two predicates are equal.
   * @param a first predicate
   * @param b second predicate
   * @return {@code true} if predicates are equal, {@code false} otherwise
   */
  static boolean areEqual(Predicate a, Predicate b) {
    return a.getEngine() == b.getEngine() && Arrays.equals(a.encodeCode(), b.encodeCode()) && Arrays.equals(
            a.encodeParameters(), b.encodeParameters());
  }
}
