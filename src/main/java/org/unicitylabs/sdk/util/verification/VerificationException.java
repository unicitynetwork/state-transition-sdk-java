package org.unicitylabs.sdk.util.verification;

public class VerificationException extends RuntimeException {

  private final VerificationResult<?> result;

  public VerificationException(String message, VerificationResult<?> result) {
    super(String.format("Verification exception { message: '%s', result: %s", message, result.toString()));

    this.result = result;
  }

  public VerificationResult<?> getVerificationResult() {
    return this.result;
  }
}
