package org.unicitylabs.sdk.consolidation;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An explicit "unavailable" outcome of the service: it cannot provide the proof material now. Not
 * an integrity failure, and no claim about the validity of the token.
 */
public final class Unavailability {

  private final ProofUnavailableReason reason;
  private final boolean retryable;
  private final String detail;
  private final List<Integer> proofIndexes;

  /**
   * Creates the outcome.
   *
   * @param reason stable reason
   * @param retryable whether a new attempt may succeed
   * @param detail service detail, may be null
   * @param proofIndexes outer proofs concerned (0 is the genesis, i the i-th transfer) when the
   *     service sent request indexes, may be null
   */
  public Unavailability(ProofUnavailableReason reason, boolean retryable, String detail,
      List<Integer> proofIndexes) {
    this.reason = Objects.requireNonNull(reason, "reason cannot be null");
    this.retryable = retryable;
    this.detail = detail;
    this.proofIndexes = proofIndexes == null ? null
        : Collections.unmodifiableList(List.copyOf(proofIndexes));
  }

  /**
   * Stable reason.
   *
   * @return reason
   */
  public ProofUnavailableReason getReason() {
    return reason;
  }

  /**
   * Whether a new attempt may succeed.
   *
   * @return retryable
   */
  public boolean isRetryable() {
    return retryable;
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
   * Outer proofs the service named.
   *
   * @return unmodifiable list, null if none
   */
  public List<Integer> getProofIndexes() {
    return proofIndexes;
  }

  @Override
  public String toString() {
    return "Unavailability{reason=" + reason + ", retryable=" + retryable + ", detail=" + detail
        + ", proofIndexes=" + proofIndexes + "}";
  }
}
