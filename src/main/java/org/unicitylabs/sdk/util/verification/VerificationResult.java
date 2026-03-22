package org.unicitylabs.sdk.util.verification;

import java.util.List;
import java.util.Objects;

public class VerificationResult<S> {

  private final String rule;
  private final S status;
  private final String message;
  private final List<VerificationResult<?>> results;

  public VerificationResult(
      String rule,
      S status,
      String message
  ) {
    this(rule, status, message, List.of());
  }

  public VerificationResult(
      String rule,
      S status
  ) {
    this(rule, status, "", List.of());
  }

  public VerificationResult(
      String rule,
      S status,
      String message,
      VerificationResult<?>... results
  ) {
    this(rule, status, message, List.of(results));
  }

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

  public String getRule() {
    return this.rule;
  }

  public S getStatus() {
    return this.status;
  }

  public String getMessage() {
    return this.message;
  }

  public List<VerificationResult<?>> getResults() {
    return this.results;
  }


  @Override
  public String toString() {
    return "VerificationResult{" +
        "rule='" + this.rule + '\'' +
        ", status=" + this.status +
        ", message='" + this.message + '\'' +
        ", results=" + this.results +
        '}';
  }
}
