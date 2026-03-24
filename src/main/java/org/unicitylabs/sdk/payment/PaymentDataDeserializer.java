package org.unicitylabs.sdk.payment;

@FunctionalInterface
public interface PaymentDataDeserializer {
  PaymentData decode(byte[] data);
}
