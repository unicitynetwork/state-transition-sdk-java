package org.unicitylabs.sdk.consolidation;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import org.unicitylabs.sdk.api.bft.verification.SealVerificationBusyException;
import org.unicitylabs.sdk.api.bft.ShardId;

/**
 * Typed failure of a consolidation attempt. The original token is never modified by any outcome.
 *
 * <p>Failures are not "unavailable" outcomes: an unavailable outcome is the service explicitly
 * reporting that it cannot serve the proof material now, see {@link ConsolidationResult}. A
 * cancellation, a deadline, a transport, server or response failure and a refusal of the material
 * complete the future of the operation exceptionally with this class. Integrity failures are never
 * disguised as unavailability: signature, authority, path and binding failures always end here.
 */
public class ConsolidationException extends RuntimeException {

  /** Why a consolidation attempt did not produce a token. */
  public enum Kind {
    /**
     * The verification capacity of the operation was used up: too many distinct seals were being
     * verified at once ({@code SealVerificationBusyException}). Nothing is known about the material;
     * retryable.
     */
    BUSY,
    /** The caller cancelled the attempt. Never retryable by the SDK. */
    CANCELLED,
    /** The token or the configuration cannot be consolidated as given. */
    INPUT,
    /** The material is cryptographically or structurally refused, see {@link ConsolidationOutcome}. */
    INTEGRITY,
    /** A response is malformed or inconsistent with the request. */
    INVALID_RESPONSE,
    /** A service refused the request itself; retrying the same input cannot succeed. */
    REJECTED,
    /** A service reported an operational failure (database, internal or unknown error code). */
    SERVER,
    /** The total deadline of the attempt expired. */
    TIMEOUT,
    /** An HTTP exchange failed. */
    TRANSPORT
  }

  private final Kind kind;
  private final ConsolidationOutcome outcome;
  private final String detail;
  private final boolean retryable;
  private final ShardId shard;

  /**
   * Creates the exception.
   *
   * @param kind classification
   * @param message message
   * @param outcome outcome of an input or integrity failure, ignored for other kinds
   * @param detail service detail echoed from a failed call, may be null
   * @param retryable whether a new attempt may succeed
   * @param shard shard concerned, may be null
   * @param cause underlying failure, may be null
   */
  public ConsolidationException(Kind kind, String message, ConsolidationOutcome outcome,
      String detail, boolean retryable, ShardId shard, Throwable cause) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "kind cannot be null");
    this.outcome = kind == Kind.INPUT || kind == Kind.INTEGRITY
        ? (outcome == null ? ConsolidationOutcome.INVALID_INPUT : outcome) : null;
    this.detail = detail;
    this.retryable = retryable;
    this.shard = shard;
  }

  /**
   * Creates an input or integrity failure.
   *
   * @param kind {@link Kind#INPUT} or {@link Kind#INTEGRITY}
   * @param outcome outcome
   * @param message message
   * @param shard shard concerned, may be null
   * @param cause underlying failure, may be null
   * @return the exception
   */
  public static ConsolidationException of(Kind kind, ConsolidationOutcome outcome, String message,
      ShardId shard, Throwable cause) {
    return new ConsolidationException(kind, message, outcome, null, false, shard, cause);
  }

  /**
   * Classifies a failure that no stage of an attempt anticipated: a resource limit of the
   * verification is {@link Kind#BUSY} and retryable, an executor that refuses the work is an
   * {@link Kind#INPUT} failure of the configuration, anything else is a malformed response.
   *
   * @param failure the failure, already unwrapped from completion wrappers
   * @return the typed exception
   */
  static ConsolidationException ofUnexpected(Throwable failure) {
    if (failure instanceof SealVerificationBusyException) {
      return new ConsolidationException(Kind.BUSY, String.valueOf(failure.getMessage()), null,
          null, true, null, failure);
    }
    if (failure instanceof RejectedExecutionException) {
      return new ConsolidationException(Kind.INPUT,
          "The configured executor rejected the work: " + failure.getMessage(),
          ConsolidationOutcome.INVALID_INPUT, null, false, null, failure);
    }
    return new ConsolidationException(Kind.INVALID_RESPONSE, "Unexpected failure: " + failure,
        ConsolidationOutcome.INVALID_INPUT, null, false, null, failure);
  }

  /**
   * Classification.
   *
   * @return kind
   */
  public Kind getKind() {
    return kind;
  }

  /**
   * Outcome of an input or integrity failure.
   *
   * @return outcome, null for the other kinds
   */
  public ConsolidationOutcome getOutcome() {
    return outcome;
  }

  /**
   * Service detail.
   *
   * @return detail, null if none
   */
  public String getDetail() {
    return detail;
  }

  /**
   * Whether a new attempt may succeed (transport failures, deadlines and a busy verification only;
   * never a cancellation).
   *
   * @return retryable
   */
  public boolean isRetryable() {
    return retryable;
  }

  /**
   * Shard concerned.
   *
   * @return shard, null if none
   */
  public ShardId getShard() {
    return shard;
  }
}
