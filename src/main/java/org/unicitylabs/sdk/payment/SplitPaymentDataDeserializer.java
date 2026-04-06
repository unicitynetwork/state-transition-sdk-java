package org.unicitylabs.sdk.payment;

/**
 * Functional contract for decoding encoded split payment data.
 */
@FunctionalInterface
public interface SplitPaymentDataDeserializer {
  /**
   * Decodes split payment data bytes.
   *
   * @param data encoded split payment data bytes
   * @return decoded split payment data
   */
  SplitPaymentData decode(byte[] data);
}
