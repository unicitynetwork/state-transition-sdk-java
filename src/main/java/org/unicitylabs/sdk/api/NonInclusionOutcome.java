package org.unicitylabs.sdk.api;

import java.util.Objects;

/**
 * Result of a successful non-inclusion verification: the queried state and the authenticated
 * snapshot, and whether the state is absent from it or (a valid path proving this exact key) present.
 * A rejection is never an outcome: it is a {@link NonInclusionVerificationException} without a
 * snapshot.
 *
 * <p>Non-inclusion proves absence only at the authenticated snapshot returned by verification. It
 * does not prove absence now, continuous absence, or that a token can be spent. Compare the
 * snapshot&rsquo;s network and root round (or its certified seal timestamp under your
 * application&rsquo;s time policy) with an independently trusted requirement. The seal timestamp is a
 * consensus-certified time, not proof of real-time freshness. Do not use unverified response
 * metadata for this check.
 *
 * <p>Instances are created by {@link NonInclusionVerifier} only.
 */
public final class NonInclusionOutcome {

  /** What the verified snapshot says about the state. */
  public enum Kind {
    /** The state is absent from the authenticated snapshot. */
    ABSENT_AT_SNAPSHOT,
    /** A valid path proves the exact key present at the authenticated snapshot. */
    INCLUDED_AT_SNAPSHOT
  }

  private final Kind kind;
  private final StateId stateId;
  private final NonInclusionSnapshot snapshot;

  NonInclusionOutcome(Kind kind, StateId stateId, NonInclusionSnapshot snapshot) {
    this.kind = Objects.requireNonNull(kind);
    this.stateId = Objects.requireNonNull(stateId);
    this.snapshot = Objects.requireNonNull(snapshot);
  }

  /**
   * What the snapshot says.
   *
   * @return kind
   */
  public Kind getKind() {
    return kind;
  }

  /**
   * Tells if the state is absent at the snapshot.
   *
   * @return true for {@link Kind#ABSENT_AT_SNAPSHOT}
   */
  public boolean isAbsent() {
    return kind == Kind.ABSENT_AT_SNAPSHOT;
  }

  /**
   * The queried state.
   *
   * @return state id
   */
  public StateId getStateId() {
    return stateId;
  }

  /**
   * The authenticated snapshot.
   *
   * @return snapshot
   */
  public NonInclusionSnapshot getSnapshot() {
    return snapshot;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof NonInclusionOutcome)) {
      return false;
    }
    NonInclusionOutcome that = (NonInclusionOutcome) o;
    return kind == that.kind && stateId.equals(that.stateId) && snapshot.equals(that.snapshot);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, stateId, snapshot);
  }

  @Override
  public String toString() {
    return "NonInclusionOutcome{" + kind + ", " + stateId + ", " + snapshot + "}";
  }
}
