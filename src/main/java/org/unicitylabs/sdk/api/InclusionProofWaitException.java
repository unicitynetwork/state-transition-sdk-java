package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationStatus;

/**
 * A wait for an inclusion proof ended for a reason that is not a transport failure: an
 * unauthenticated status hint, or a proof for another request. Unchecked, and completes the wait
 * future exceptionally.
 */
public abstract class InclusionProofWaitException extends RuntimeException {

  private final StateId stateId;

  InclusionProofWaitException(StateId stateId, String message) {
    super(message);
    this.stateId = stateId;
  }

  /**
   * The state waited for.
   *
   * @return state id
   */
  public StateId getStateId() {
    return stateId;
  }

  /**
   * Tells if the same signed request may be submitted again.
   *
   * @return true for {@link Expired} and {@link Unknown}
   */
  public abstract boolean isResubmittable();

  /** The aggregator hints the request as expired: it was dropped before it was included. */
  public static final class Expired extends InclusionProofWaitException {

    /**
     * Creates the exception.
     *
     * @param stateId state id
     */
    public Expired(StateId stateId) {
      super(stateId, "The aggregator reports " + stateId + " as expired: the request was dropped "
          + "before it was included. This is an unauthenticated hint; the same signed request may "
          + "be submitted again.");
    }

    @Override
    public boolean isResubmittable() {
      return true;
    }
  }

  /** The aggregator keeps hinting the request as unknown for longer than the grace period. */
  public static final class Unknown extends InclusionProofWaitException {

    private final long waitedMillis;

    /**
     * Creates the exception.
     *
     * @param stateId state id
     * @param waitedMillis time the state was unknown for
     */
    public Unknown(StateId stateId, long waitedMillis) {
      super(stateId, "The aggregator does not know " + stateId + " (unknown for " + waitedMillis
          + " ms): not submitted to this aggregator, misrouted, lost or dropped. This is an "
          + "unauthenticated hint, not a proof of absence or of the state being unspent; the same "
          + "signed request may be submitted again.");
      this.waitedMillis = waitedMillis;
    }

    /**
     * Time the state was unknown for.
     *
     * @return milliseconds
     */
    public long getWaitedMillis() {
      return waitedMillis;
    }

    @Override
    public boolean isResubmittable() {
      return true;
    }
  }

  /**
   * The aggregator certified another request for the state id. The proof was authenticated first,
   * so this is not a verification failure of a forged proof. Not resubmittable.
   */
  public static final class Conflict extends InclusionProofWaitException {

    private final InclusionProofVerificationStatus reason;
    private final InclusionProof authenticatedProof;

    /**
     * Creates the exception.
     *
     * @param reason the mismatch that was found
     * @param stateId state id
     * @param authenticatedProof the proof of the other request, authenticated as a proof for the
     *     state id
     */
    public Conflict(InclusionProofVerificationStatus reason, StateId stateId,
        InclusionProof authenticatedProof) {
      super(stateId, "Invalid inclusion proof status: " + reason + ": the aggregator certified "
          + "another request for " + stateId + "; this transaction was not (and cannot be) "
          + "certified for that state.");
      this.reason = reason;
      this.authenticatedProof = authenticatedProof;
    }

    /**
     * The mismatch that was found.
     *
     * @return transaction hash or certification data mismatch
     */
    public InclusionProofVerificationStatus getReason() {
      return reason;
    }

    /**
     * The proof of the other request.
     *
     * @return authenticated proof
     */
    public InclusionProof getAuthenticatedProof() {
      return authenticatedProof;
    }

    @Override
    public boolean isResubmittable() {
      return false;
    }
  }
}
