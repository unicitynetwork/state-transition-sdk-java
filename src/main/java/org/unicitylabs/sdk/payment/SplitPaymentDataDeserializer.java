package org.unicitylabs.sdk.payment;

@FunctionalInterface
public interface SplitPaymentDataDeserializer {
  SplitPaymentData decode(byte[] data);
}
