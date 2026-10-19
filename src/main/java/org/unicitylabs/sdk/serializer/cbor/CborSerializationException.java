package org.unicitylabs.sdk.serializer.cbor;

/**
 * CBOR serialization exception, when something goes wrong with deserializing or serializing.
 */
public class CborSerializationException extends RuntimeException {

  /**
   * Create CBOR serialization exception.
   *
   * @param message error message
   */
  public CborSerializationException(String message) {
    super(message);
  }

  /**
   * Create CBOR serialization exception with its cause.
   *
   * @param message error message
   * @param cause underlying failure
   */
  public CborSerializationException(String message, Throwable cause) {
    super(message, cause);
  }
}
