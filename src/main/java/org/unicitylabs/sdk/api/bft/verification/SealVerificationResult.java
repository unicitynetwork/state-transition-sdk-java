package org.unicitylabs.sdk.api.bft.verification;

import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Outcome of a seal check of a {@link SealVerificationSession}: the rule result tree plus the typed
 * reason of a failure.
 */
public final class SealVerificationResult extends VerificationResult<VerificationStatus> {

  private final UnicityCertificateFailure failure;

  private SealVerificationResult(String rule, VerificationStatus status, String message,
      UnicityCertificateFailure failure, List<VerificationResult<?>> results) {
    super(rule, status, message, results);
    this.failure = failure;
  }

  static SealVerificationResult ok(String rule, List<VerificationResult<?>> results) {
    return new SealVerificationResult(rule, VerificationStatus.OK, "", null, results);
  }

  static SealVerificationResult fail(String rule, UnicityCertificateFailure failure,
      String message, List<VerificationResult<?>> results) {
    return new SealVerificationResult(rule, VerificationStatus.FAIL, message,
        Objects.requireNonNull(failure), results);
  }

  /**
   * Why the seal was not accepted.
   *
   * @return typed reason, null on success
   */
  public UnicityCertificateFailure getFailure() {
    return failure;
  }
}
