package org.unicitylabs.sdk.consolidation;

/**
 * Result of one attempt: rebuilt proofs, or an explicit unavailable outcome of the service.
 * Cancellation, deadline, transport, server, response and integrity failures are not results, they
 * complete the future exceptionally with a {@link ConsolidationException}.
 */
public final class ConsolidationAttempt {

  /** Outcome status. */
  public enum Status {
    /** The proofs were rebuilt under one master seal. */
    CONSOLIDATED,
    /** The service cannot provide the proof material now. */
    UNAVAILABLE
  }

  private final Status status;
  private final ConsolidatedProofs proofs;
  private final Unavailability unavailability;

  private ConsolidationAttempt(Status status, ConsolidatedProofs proofs,
      Unavailability unavailability) {
    this.status = status;
    this.proofs = proofs;
    this.unavailability = unavailability;
  }

  static ConsolidationAttempt consolidated(ConsolidatedProofs proofs) {
    return new ConsolidationAttempt(Status.CONSOLIDATED, proofs, null);
  }

  static ConsolidationAttempt unavailable(Unavailability unavailability) {
    return new ConsolidationAttempt(Status.UNAVAILABLE, null, unavailability);
  }

  /**
   * Status.
   *
   * @return status
   */
  public Status getStatus() {
    return status;
  }

  /**
   * The rebuilt proofs.
   *
   * @return proofs, null if unavailable
   */
  public ConsolidatedProofs getProofs() {
    return proofs;
  }

  /**
   * The unavailable outcome.
   *
   * @return unavailability, null if consolidated
   */
  public Unavailability getUnavailability() {
    return unavailability;
  }
}
