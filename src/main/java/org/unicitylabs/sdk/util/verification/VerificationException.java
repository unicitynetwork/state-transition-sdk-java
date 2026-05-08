package org.unicitylabs.sdk.util.verification;

/**
 * Exception thrown when a verification flow returns a failing result.
 */
public class VerificationException extends RuntimeException {
  /**
   * Verification result associated with this exception.
   */
  private final VerificationResult<?> result;

  /**
   * Creates a verification exception with message and failing verification result.
   *
   * @param message verification failure message
   * @param result verification result associated with the failure
   */
  public VerificationException(String message, VerificationResult<?> result) {
    super(String.format("Verification exception { message: '%s', result: %s", message, result.toString()));

    this.result = result;
  }

  /**
   * Returns the verification result associated with this exception.
   *
   * @return verification result
   */
  public VerificationResult<?> getVerificationResult() {
    return this.result;
  }
}
