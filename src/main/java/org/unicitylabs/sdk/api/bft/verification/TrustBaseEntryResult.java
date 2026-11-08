package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/** Result of picking the trust-base entry a seal is checked against. On success it carries the entry. */
public class TrustBaseEntryResult extends VerificationResult<VerificationStatus> {

  /** Rule name of the placement. */
  public static final String RULE = "UnicitySealTrustBaseEntryRule";

  private final TrustBaseEntry entry;
  private final UnicityCertificateFailure failure;

  /**
   * Create a result.
   *
   * @param status OK or FAIL
   * @param message what failed
   * @param entry the entry of the seal's epoch on success, null otherwise
   * @param failure the typed reason of a failure, null on success
   */
  public TrustBaseEntryResult(VerificationStatus status, String message, TrustBaseEntry entry,
      UnicityCertificateFailure failure) {
    super(RULE, status, message);
    this.entry = entry;
    this.failure = failure;
  }

  /**
   * Get the typed reason of a failure.
   *
   * @return the reason, null on success
   */
  public UnicityCertificateFailure getFailure() {
    return this.failure;
  }

  /**
   * Get the entry.
   *
   * @return the entry of the seal's epoch, null on failure
   */
  public TrustBaseEntry getEntry() {
    return this.entry;
  }
}
