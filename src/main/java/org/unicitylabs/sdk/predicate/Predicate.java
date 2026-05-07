package org.unicitylabs.sdk.predicate;

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
}
