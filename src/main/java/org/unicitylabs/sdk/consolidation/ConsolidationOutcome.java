package org.unicitylabs.sdk.consolidation;

/**
 * Stable reason of an {@link ConsolidationException.Kind#INTEGRITY} or
 * {@link ConsolidationException.Kind#INPUT} failure. Mirrors the reference implementation's outcome
 * names so that shared fixtures bind to them rather than to message text.
 */
public enum ConsolidationOutcome {
  /** A proof is not bound to the input it replaces, or to the target root. */
  BINDING_MISMATCH,
  /** An original request expired at its reference time. */
  EXPIRED,
  /** The material is of another lineage: shard, network or structure that does not fit. */
  INCOMPATIBLE_LINEAGE,
  /** The token, the configuration or the material is not valid input. */
  INVALID_INPUT,
  /** A reference time is after the timestamp of the target. */
  REFERENCE_TIME_AFTER_TARGET,
  /** A seal is not authenticated by the trusted authority. */
  UNAUTHENTICATED,
  /** The material is of a kind this consolidation does not support. */
  UNSUPPORTED
}
