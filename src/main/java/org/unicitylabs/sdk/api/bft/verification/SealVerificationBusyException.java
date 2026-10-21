package org.unicitylabs.sdk.api.bft.verification;

/**
 * The session already runs as many distinct seal checks as it allows. A resource limit of the
 * operation, not a verification result: nothing about the seal is known. Retry later or verify with
 * a larger limit.
 */
public class SealVerificationBusyException extends RuntimeException {

  private final int maxInFlight;

  /**
   * Creates the exception.
   *
   * @param maxInFlight the session's limit
   */
  public SealVerificationBusyException(int maxInFlight) {
    super("Too many concurrent seal verifications in the session (limit " + maxInFlight + ").");
    this.maxInFlight = maxInFlight;
  }

  /**
   * The session's limit.
   *
   * @return most checks in flight
   */
  public int getMaxInFlight() {
    return maxInFlight;
  }
}
