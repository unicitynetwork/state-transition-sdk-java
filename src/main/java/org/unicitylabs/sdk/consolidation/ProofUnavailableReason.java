package org.unicitylabs.sdk.consolidation;

/**
 * Stable reason why the service cannot provide the proof material. The original token is intact and
 * no claim is made about its validity.
 *
 * <p>The four {@code STATE_*} reasons carry the aggregator's status hints, which are
 * unauthenticated: they classify the failure, they never make the token invalid or the state
 * unspent.
 */
public enum ProofUnavailableReason {
  /** The service is under pressure. */
  BUSY,
  /** The token needs more states per shard than one batch carries (no chunking is attempted). */
  LIMIT_EXCEEDED,
  /** The master served no partition view. */
  PARTITION_VIEW_UNAVAILABLE,
  /**
   * The master view does not cover the history yet (it is older than the original proofs, or no
   * finalized view exists). A later attempt may succeed.
   */
  PENDING_PUBLICATION,
  /** A shard reports a request of the token as dropped before inclusion; not retryable. */
  STATE_EXPIRED,
  /** The state is not in the root the master view commits to for that shard; not retryable. */
  STATE_NOT_IN_ROOT,
  /** A shard accepted a request of the token but has not certified it yet; retry later. */
  STATE_PENDING,
  /**
   * A shard does not know a request of the token (misrouted, lost, restarted, another instance);
   * a routing or input problem, not retryable.
   */
  STATE_UNKNOWN,
  /**
   * A required view or state is not (or no longer) retained by a shard; a later attempt against a
   * fresh latest view may succeed, the same root cannot be repaired by waiting.
   */
  TARGET_NOT_RETAINED,
  /** The endpoint (or client) has no batch proof method. */
  UNSUPPORTED_CAPABILITY,
  /** The endpoint serves the method but not for this configuration (for example parent/child). */
  UNSUPPORTED_CONFIGURATION
}
