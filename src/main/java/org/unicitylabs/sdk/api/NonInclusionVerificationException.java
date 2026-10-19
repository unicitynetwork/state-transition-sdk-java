package org.unicitylabs.sdk.api;

import java.util.Objects;

/**
 * A non-inclusion proof was rejected. Carries a rejection class and never a snapshot: nothing of
 * an unauthenticated certificate is exposed.
 */
public class NonInclusionVerificationException extends RuntimeException {

  /** Rejection classes, in the order the verifier reaches them. */
  public enum Reason {
    /** The wrapper, certificate or unicity certificate is not in the exact profile. */
    MALFORMED,
    /** The unicity certificate body, partition, shard path, configuration pin or seal hash is wrong. */
    UC_INVALID,
    /** The seal is not authenticated by the trusted authority, or is past the authority's end. */
    AUTHORITY_REJECTED,
    /** Shard membership or the state-tree relation does not hold. */
    CERTIFICATE_INVALID,
    /** The proof is bound to another state than the one asked about. */
    TARGET_MISMATCH
  }

  private static final long serialVersionUID = 1L;

  private final Reason reason;

  /**
   * Creates the exception.
   *
   * @param reason rejection class
   * @param message detail without peer-controlled data
   * @param cause cause, may be null
   */
  public NonInclusionVerificationException(Reason reason, String message, Throwable cause) {
    super(Objects.requireNonNull(reason, "reason cannot be null") + ": " + message, cause);
    this.reason = reason;
  }

  /**
   * Rejection class.
   *
   * @return reason
   */
  public Reason getReason() {
    return reason;
  }
}
