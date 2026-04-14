package org.unicitylabs.sdk.api.bft.verification;

import java.util.List;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Verification result type for unicity certificate verification.
 */
public class UnicityCertificateVerificationResult extends VerificationResult<VerificationStatus> {

  private UnicityCertificateVerificationResult(VerificationStatus status,
      List<VerificationResult<?>> results) {
    super("UnicityCertificateVerification", status, "", results);
  }

  /**
   * Creates a failed unicity certificate verification result.
   *
   * @param results detailed rule verification results
   * @return failed verification result
   */
  public static UnicityCertificateVerificationResult fail(List<VerificationResult<?>> results) {
    return new UnicityCertificateVerificationResult(VerificationStatus.FAIL, results);
  }

  /**
   * Creates a successful unicity certificate verification result.
   *
   * @param results detailed rule verification results
   * @return successful verification result
   */
  public static UnicityCertificateVerificationResult ok(List<VerificationResult<?>> results) {
    return new UnicityCertificateVerificationResult(VerificationStatus.OK, results);
  }
}
