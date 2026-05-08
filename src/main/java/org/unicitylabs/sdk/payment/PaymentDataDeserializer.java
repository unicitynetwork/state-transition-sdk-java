package org.unicitylabs.sdk.payment;

/**
 * Functional contract for decoding encoded payment data.
 */
@FunctionalInterface
public interface PaymentDataDeserializer {
  /**
   * Decodes payment data bytes into a {@link PaymentData} instance.
   *
   * @param data encoded payment data bytes
   * @return decoded payment data
   */
  PaymentData decode(byte[] data);
}
