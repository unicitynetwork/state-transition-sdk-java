package org.unicitylabs.sdk.consolidation;

import org.unicitylabs.sdk.transaction.Token;

/**
 * Outcome of {@link TokenProofClient#consolidate}. Immutable: either a consolidated, fully
 * verified token with its summary, or an explicit unavailable outcome with a stable reason.
 * Cancellation, deadline, transport, server, response and integrity failures complete the future
 * exceptionally with a {@link ConsolidationException}, never reported as unavailable. The original
 * token is intact on every outcome.
 */
public final class ConsolidationResult {

  /** Outcome status. */
  public enum Status {
    /** A token whose outer-history proofs all share one seal. */
    CONSOLIDATED,
    /** The service cannot provide the proof material now. */
    UNAVAILABLE
  }

  private final Status status;
  private final Token token;
  private final ProofSummary summary;
  private final Unavailability unavailability;

  private ConsolidationResult(Status status, Token token, ProofSummary summary,
      Unavailability unavailability) {
    this.status = status;
    this.token = token;
    this.summary = summary;
    this.unavailability = unavailability;
  }

  static ConsolidationResult consolidated(Token token, ProofSummary summary) {
    return new ConsolidationResult(Status.CONSOLIDATED, token, summary, null);
  }

  static ConsolidationResult unavailable(Unavailability unavailability) {
    return new ConsolidationResult(Status.UNAVAILABLE, null, null, unavailability);
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
   * The consolidated token.
   *
   * @return token, null if unavailable
   */
  public Token getToken() {
    return token;
  }

  /**
   * Diagnostics of a consolidation.
   *
   * @return summary, null if unavailable
   */
  public ProofSummary getSummary() {
    return summary;
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
