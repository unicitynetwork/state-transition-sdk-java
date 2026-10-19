package org.unicitylabs.sdk.api;

/** The aggregator rejected a certification request with a status other than success. */
public class CertificationRejectedException extends RuntimeException {

  private final CertificationStatus status;

  /**
   * Creates the exception.
   *
   * @param status status of the aggregator's answer
   */
  public CertificationRejectedException(CertificationStatus status) {
    super("Certification request failed with status '" + status + "'");
    this.status = status;
  }

  /**
   * Status of the aggregator's answer.
   *
   * @return status
   */
  public CertificationStatus getStatus() {
    return status;
  }
}
