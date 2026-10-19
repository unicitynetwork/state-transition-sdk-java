package org.unicitylabs.sdk.api.bft.verification;

import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Verification result type for unicity certificate verification.
 */
public class UnicityCertificateVerificationResult extends VerificationResult<VerificationStatus> {

  private final UnicityCertificateFailure failure;

  private UnicityCertificateVerificationResult(VerificationStatus status,
                                               UnicityCertificateFailure failure,
                                               List<VerificationResult<?>> results) {
    super("UnicityCertificateVerification", status, "", results);
    this.failure = failure;
  }

  /**
   * Creates a failed unicity certificate verification result without a typed reason.
   *
   * @param results detailed rule verification results
   * @return failed verification result
   */
  public static UnicityCertificateVerificationResult fail(List<VerificationResult<?>> results) {
    return new UnicityCertificateVerificationResult(VerificationStatus.FAIL, null, results);
  }

  /**
   * Creates a failed unicity certificate verification result with its typed reason.
   *
   * @param failure why the certificate was not accepted
   * @param results detailed rule verification results
   * @return failed verification result
   */
  public static UnicityCertificateVerificationResult fail(UnicityCertificateFailure failure,
                                                          List<VerificationResult<?>> results) {
    return new UnicityCertificateVerificationResult(VerificationStatus.FAIL,
            Objects.requireNonNull(failure, "failure cannot be null"), results);
  }

  /**
   * Creates a successful unicity certificate verification result.
   *
   * @param results detailed rule verification results
   * @return successful verification result
   */
  public static UnicityCertificateVerificationResult ok(List<VerificationResult<?>> results) {
    return new UnicityCertificateVerificationResult(VerificationStatus.OK, null, results);
  }

  /**
   * Why the certificate was not accepted.
   *
   * @return the typed reason of the first failing check, null on success
   */
  public UnicityCertificateFailure getFailure() {
    return failure;
  }
}
