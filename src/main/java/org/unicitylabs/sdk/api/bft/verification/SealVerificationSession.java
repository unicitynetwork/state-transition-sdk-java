package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Seal authentication under a snapshot of the caller's trust base, with a bounded memo of
 * successful checks and sharing of checks in flight.
 *
 * <p>The memo key binds the complete seal bytes including the signature witnesses, the authority
 * content and the policy. Only successes are retained, failures and failed in-flight checks are
 * removed, the memo is bounded and evicts the oldest entry. Network, epoch and round checks run on
 * every call, hits included. The session does not accept a custom rule silently: an injected rule
 * is called every time and its result is never memoized.
 *
 * <p>Thread safe. Instances are scoped to one operation; they are not a process-wide cache.
 */
public final class SealVerificationSession implements SealAuthenticator {

  /** Default size of the memo of successful checks. */
  public static final int DEFAULT_MAX_ENTRIES = 64;
  /** Default most concurrent verifications in flight. */
  public static final int DEFAULT_MAX_IN_FLIGHT = 64;

  private SealVerificationSession() {
  }

  /**
   * Creates a session over a snapshot of the trust base. Later changes of the passed object do not
   * affect the session.
   *
   * @param trustBase the authority
   * @param policy the policy the session applies
   * @return new session with the default limits
   */
  public static SealVerificationSession create(RootTrustBase trustBase, VerificationPolicy policy) {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Creates a session with explicit limits.
   *
   * @param trustBase the authority
   * @param policy the policy the session applies
   * @param maxEntries memo size, positive
   * @param maxInFlight most verifications in flight, positive
   * @return new session
   * @throws IllegalArgumentException if a limit is not positive
   */
  public static SealVerificationSession create(RootTrustBase trustBase, VerificationPolicy policy,
      int maxEntries, int maxInFlight) {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  @Override
  public String getIdentity() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * The policy of this session.
   *
   * @return policy
   */
  public VerificationPolicy getPolicy() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  @Override
  public VerificationResult<VerificationStatus> authenticate(UnicitySeal seal) {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Work counters of the session.
   *
   * @return snapshot of the counters
   */
  public SealVerificationStats getStats() {
    throw new UnsupportedOperationException("Implemented by package A");
  }
}
