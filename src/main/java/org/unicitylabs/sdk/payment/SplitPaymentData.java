package org.unicitylabs.sdk.payment;

/**
 * Payment data for already split payments.
 */
public interface SplitPaymentData extends PaymentData {
  /**
   * Returns the reason associated with the split.
   *
   * @return split reason
   */
  SplitReason getReason();
}
