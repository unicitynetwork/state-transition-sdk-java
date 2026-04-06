package org.unicitylabs.sdk.util.verification;

import java.util.List;
import java.util.Objects;

/**
 * Generic verification result containing status, message and optional nested rule results.
 *
 * @param <S> status enum/type used by the verification rule
 */
public class VerificationResult<S> {

  private final String rule;
  private final S status;
  private final String message;
  private final List<VerificationResult<?>> results;

  /**
   * Create verification result with no nested results.
   *
   * @param rule verification rule name
   * @param status verification status
   * @param message descriptive message
   */
  public VerificationResult(
      String rule,
      S status,
      String message
  ) {
    this(rule, status, message, List.of());
  }

  /**
   * Create verification result with empty message and no nested results.
   *
   * @param rule verification rule name
   * @param status verification status
   */
  public VerificationResult(
      String rule,
      S status
  ) {
    this(rule, status, "", List.of());
  }

  /**
   * Create verification result with nested results as varargs.
   *
   * @param rule verification rule name
   * @param status verification status
   * @param message descriptive message
   * @param results nested verification results
   */
  public VerificationResult(
      String rule,
      S status,
      String message,
      VerificationResult<?>... results
  ) {
    this(rule, status, message, List.of(results));
  }

  /**
   * Create verification result.
   *
   * @param rule verification rule name
   * @param status verification status
   * @param message descriptive message
   * @param results nested verification results
   */
  public VerificationResult(
      String rule,
      S status,
      String message,
      List<VerificationResult<?>> results
  ) {
    Objects.requireNonNull(rule, "Rule cannot be null");
    Objects.requireNonNull(status, "Status cannot be null");
    Objects.requireNonNull(message, "Message cannot be null");
    Objects.requireNonNull(results, "Results cannot be null");

    this.rule = rule;
    this.status = status;
    this.message = message;
    this.results = List.copyOf(results);
  }

  /**
   * Get verification rule name.
   *
   * @return rule name
   */
  public String getRule() {
    return this.rule;
  }

  /**
   * Get verification status.
   *
   * @return verification status
   */
  public S getStatus() {
    return this.status;
  }

  /**
   * Get verification message.
   *
   * @return verification message
   */
  public String getMessage() {
    return this.message;
  }

  /**
   * Get nested verification results.
   *
   * @return nested results
   */
  public List<VerificationResult<?>> getResults() {
    return this.results;
  }


  @Override
  public String toString() {
    return String.format(
        "VerificationResult{rule=%s, status=%s, message=%s, results=%s}",
        this.rule,
        this.status,
        this.message,
        this.results
    );
  }
}
