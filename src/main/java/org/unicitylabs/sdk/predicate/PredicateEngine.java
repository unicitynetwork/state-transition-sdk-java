package org.unicitylabs.sdk.predicate;

/**
 * Enumerates supported predicate engines and their numeric identifiers.
 */
public enum PredicateEngine {
  /** Engine for built-in predicate implementations. */
  BUILT_IN(1);

  private final int id;

  PredicateEngine(int id) {
    this.id = id;
  }

  /**
   * Returns the numeric identifier of this predicate engine.
   *
   * @return predicate engine id
   */
  public int getId() {
    return this.id;
  }

  /**
   * Resolves a predicate engine from its numeric identifier.
   *
   * @param id predicate engine id
   * @return matching predicate engine
   * @throws IllegalArgumentException if the id is not mapped to a predicate engine
   */
  public static PredicateEngine fromId(int id) {
    for (PredicateEngine engine : PredicateEngine.values()) {
      if (engine.id == id) {
        return engine;
      }
    }
    throw new IllegalArgumentException("Invalid predicate engine: " + id);
  }
}
