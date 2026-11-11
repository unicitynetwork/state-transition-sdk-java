package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.util.UnsignedLongs;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * The typed "no entry for this epoch" failure: the held history has no entry for the epoch of a
 * seal. The caller can obtain the missing entries (the update step of a later release), append
 * them to the history, and verify again. It is a different failure from a bad signature or a seal
 * outside its epoch: nothing is wrong with the proof as far as is known.
 */
public final class NoTrustBaseEntryResult extends TrustBaseEntryResult {

  private final long epoch;
  private final Long heldTipEpoch;

  /**
   * Create the result.
   *
   * @param epoch epoch of the seal, unsigned bits
   * @param heldTipEpoch epoch of the held tip, null when the history is empty
   */
  public NoTrustBaseEntryResult(long epoch, Long heldTipEpoch) {
    super(VerificationStatus.FAIL, "No trust-base entry for epoch " + UnsignedLongs.toDecimal(epoch)
        + " (held tip: " + (heldTipEpoch == null ? "none" : UnsignedLongs.toDecimal(heldTipEpoch))
        + ").", null, UnicityCertificateFailure.NO_TRUST_BASE_ENTRY);
    this.epoch = epoch;
    this.heldTipEpoch = heldTipEpoch;
  }

  /**
   * Get the epoch the seal needs.
   *
   * @return epoch, unsigned bits
   */
  public long getEpoch() {
    return this.epoch;
  }

  /**
   * Get the epoch of the held tip.
   *
   * @return epoch, unsigned bits, or null when the history is empty
   */
  public Long getHeldTipEpoch() {
    return this.heldTipEpoch;
  }

  /**
   * The same information as an exception, for the places that report a refusal as an exception
   * (non-inclusion, consolidation) and must not lose the typed signal.
   *
   * @return the typed cause
   */
  public NoTrustBaseEntryException toException() {
    return new NoTrustBaseEntryException(this.epoch, this.heldTipEpoch);
  }

  /**
   * Find the typed no-entry failure anywhere in a verification trace.
   *
   * @param result any verification result
   * @return the first no-entry failure in the trace, or null when the failure (if any) is of
   *     another kind
   */
  public static NoTrustBaseEntryResult find(VerificationResult<?> result) {
    if (result instanceof NoTrustBaseEntryResult) {
      return (NoTrustBaseEntryResult) result;
    }
    for (VerificationResult<?> child : result.getResults()) {
      NoTrustBaseEntryResult found = find(child);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
