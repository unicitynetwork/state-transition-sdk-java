package org.unicitylabs.sdk.predicate.builtin;

/**
 * Enumerates supported built-in predicate types and their numeric identifiers.
 */
public enum BuiltInPredicateType {
  /** Predicate that locks state to a public key. */
  SIGNATURE(0x01),
  /** Predicate that marks state as unspendable (burned). */
  BURN(0x02),
  /** Predicate that references a Unicity identifier. */
  UNICITY_ID(0x100);

  private final int id;

  BuiltInPredicateType(int id) {
    this.id = id;
  }

  /**
   * Returns the numeric identifier of this predicate type.
   *
   * @return predicate type id
   */
  public int getId() {
    return this.id;
  }

  /**
   * Resolves a predicate type from its numeric identifier.
   *
   * @param id the predicate type id
   * @return the matching {@link BuiltInPredicateType}
   * @throws IllegalArgumentException if the id is not mapped to a built-in type
   */
  public static BuiltInPredicateType fromId(int id) {
    for (BuiltInPredicateType type : BuiltInPredicateType.values()) {
      if (type.id == id) {
        return type;
      }
    }
    throw new IllegalArgumentException("Invalid predicate type: " + id);
  }
}
