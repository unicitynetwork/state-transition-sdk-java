package org.unicitylabs.sdk.api.bft.verification;

import java.util.List;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class UnicityCertificateVerificationResult extends VerificationResult<VerificationStatus> {

  private UnicityCertificateVerificationResult(VerificationStatus status,
      List<VerificationResult<?>> results) {
    super("UnicityCertificateVerification", status, "", results);
  }

  public static UnicityCertificateVerificationResult fail(List<VerificationResult<?>> results) {
    return new UnicityCertificateVerificationResult(VerificationStatus.FAIL, results);
  }

  public static UnicityCertificateVerificationResult ok(List<VerificationResult<?>> results) {
    return new UnicityCertificateVerificationResult(VerificationStatus.OK, results);
  }
}
