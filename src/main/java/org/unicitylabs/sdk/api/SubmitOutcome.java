package org.unicitylabs.sdk.api;

import java.util.Objects;

/**
 * Outcome of an idempotent submission ({@code StateTransitionClient.resubmitCertificationRequest}).
 *
 * <p>A certification request is signed and deterministic, so sending it again is the same request
 * and the aggregator never creates a second leaf for it. {@link Kind#ALREADY_SUBMITTED} reports by
 * state id only: a different request for the same state, such as a conflicting spend, is reported
 * the same way. Wait for the proof and compare it.
 */
public final class SubmitOutcome {

  /** Kind of the outcome. */
  public enum Kind {
    /** The aggregator accepted the request. */
    ACCEPTED,
    /** The aggregator already holds a record for the state id. */
    ALREADY_SUBMITTED
  }

  private final Kind kind;
  private final CertificationResponse response;

  /**
   * Creates an outcome.
   *
   * @param kind kind
   * @param response the aggregator's answer, a synthetic {@code STATE_ID_QUEUED} one when the
   *     duplicate was reported as an internal error
   */
  public SubmitOutcome(Kind kind, CertificationResponse response) {
    this.kind = Objects.requireNonNull(kind, "kind cannot be null");
    this.response = Objects.requireNonNull(response, "response cannot be null");
  }

  /**
   * Kind of the outcome.
   *
   * @return kind
   */
  public Kind getKind() {
    return kind;
  }

  /**
   * The aggregator's answer.
   *
   * @return response
   */
  public CertificationResponse getResponse() {
    return response;
  }
}
