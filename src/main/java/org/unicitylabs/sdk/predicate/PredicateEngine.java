package org.unicitylabs.sdk.predicate;

public enum PredicateEngine {
  BUILT_IN(1);

  public final int id;

  PredicateEngine(int id) {
    this.id = id;
  }

  public int getId() {
    return this.id;
  }

  public static PredicateEngine fromId(int id) {
    for (PredicateEngine engine : PredicateEngine.values()) {
      if (engine.id == id) {
        return engine;
      }
    }
    return null;
  }
}
