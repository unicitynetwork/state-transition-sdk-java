package org.unicitylabs.sdk.predicate.builtin;

public enum BuiltInPredicateType {
  PAY_TO_PUBLIC_KEY(1),
  UNICITY_ID(2),
  BURN(3);

  private final int id;

  BuiltInPredicateType(int id) {
    this.id = id;
  }

  public int getId() {
    return this.id;
  }

  public static BuiltInPredicateType fromId(int id) {
    for (BuiltInPredicateType type : BuiltInPredicateType.values()) {
      if (type.id == id) {
        return type;
      }
    }
    return null;
  }
}
