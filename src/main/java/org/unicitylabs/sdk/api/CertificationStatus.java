package org.unicitylabs.sdk.api;

/**
 * Status codes for certification.
 */
public enum CertificationStatus {
  /**
   * The certification request was accepted and stored.
   */
  SUCCESS("SUCCESS"),

  /**
   * The certification request failed because the state ID already exists.
   */
  STATE_ID_EXISTS("STATE_ID_EXISTS"),
  /**
   * The certification request failed because the state ID does not match the expected format.
   */
  STATE_ID_MISMATCH("STATE_ID_MISMATCH"),
  /**
   * The certification request failed because the signature verification failed.
   */
  SIGNATURE_VERIFICATION_FAILED("SIGNATURE_VERIFICATION_FAILED"),
  /**
   * The certification request failed because signature has invalid format.
   */
  INVALID_SIGNATURE_FORMAT("INVALID_SIGNATURE_FORMAT"),
  /**
   * The certification request failed because the public key has invalid format.
   */
  INVALID_PUBLIC_KEY_FORMAT("INVALID_PUBLIC_KEY_FORMAT"),
  /**
   * The certification request failed because the source state hash has invalid format.
   */
  INVALID_SOURCE_STATE_HASH_FORMAT("INVALID_SOURCE_STATE_HASH_FORMAT"),
  /**
   * The certification request failed because the transaction hash has invalid format.
   */
  INVALID_TRANSACTION_HASH_FORMAT("INVALID_TRANSACTION_HASH_FORMAT"),
  /**
   * The certification request failed because the algorithm is not supported.
   */
  UNSUPPORTED_ALGORITHM("UNSUPPORTED_ALGORITHM"),
  /**
   * The certification request failed because request was sent to invalid shard.
   */
  INVALID_SHARD("INVALID_SHARD");

  private final String value;

  CertificationStatus(String value) {
    this.value = value;
  }

  /**
   * Get string value of the status.
   *
   * @return string value
   */
  public String getValue() {
    return value;
  }

  /**
   * Create status from string value.
   *
   * @param value string value
   * @return status
   */
  public static CertificationStatus fromString(String value) {
    for (CertificationStatus status : CertificationStatus.values()) {
      if (status.value.equalsIgnoreCase(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("Unknown status: " + value);
  }
}