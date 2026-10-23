package org.unicitylabs.sdk.payment;

/**
 * Thrown when a split output's payment data does not decode, with the split's decoder, to the
 * assets it declares. Raised before any burn is constructed.
 */
public class SplitPayloadMismatchException extends IllegalArgumentException {

  /**
   * Create the exception.
   *
   * @param message what did not match
   */
  public SplitPayloadMismatchException(String message) {
    super(message);
  }
}
