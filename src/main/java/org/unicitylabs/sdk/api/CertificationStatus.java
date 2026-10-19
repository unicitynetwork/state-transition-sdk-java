package org.unicitylabs.sdk.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Status codes for certification. Known statuses are exposed as singleton
 * constants; any other value returned by the aggregator is accepted and
 * preserved as a custom status instead of being rejected.
 */
public final class CertificationStatus {

  /**
   * The certification request was accepted and stored.
   */
  public static final CertificationStatus SUCCESS = new CertificationStatus("SUCCESS");

  /**
   * The certification request failed because the state ID does not match the expected format.
   */
  public static final CertificationStatus STATE_ID_MISMATCH =
      new CertificationStatus("STATE_ID_MISMATCH");
  /**
   * The certification request failed because the signature verification failed.
   */
  public static final CertificationStatus SIGNATURE_VERIFICATION_FAILED =
      new CertificationStatus("SIGNATURE_VERIFICATION_FAILED");
  /**
   * The certification request failed because signature has invalid format.
   */
  public static final CertificationStatus INVALID_SIGNATURE_FORMAT =
      new CertificationStatus("INVALID_SIGNATURE_FORMAT");
  /**
   * The certification request failed because the public key has invalid format.
   */
  public static final CertificationStatus INVALID_PUBLIC_KEY_FORMAT =
      new CertificationStatus("INVALID_PUBLIC_KEY_FORMAT");
  /**
   * The certification request failed because the source state hash has invalid format.
   */
  public static final CertificationStatus INVALID_SOURCE_STATE_HASH_FORMAT =
      new CertificationStatus("INVALID_SOURCE_STATE_HASH_FORMAT");
  /**
   * The certification request failed because the transaction hash has invalid format.
   */
  public static final CertificationStatus INVALID_TRANSACTION_HASH_FORMAT =
      new CertificationStatus("INVALID_TRANSACTION_HASH_FORMAT");
  /**
   * The certification request failed because the algorithm is not supported.
   */
  public static final CertificationStatus UNSUPPORTED_ALGORITHM =
      new CertificationStatus("UNSUPPORTED_ALGORITHM");
  /**
   * The certification request failed because request was sent to invalid shard.
   */
  public static final CertificationStatus INVALID_SHARD = new CertificationStatus("INVALID_SHARD");
  /**
   * The round's reference time had already reached the request's timeout.
   */
  public static final CertificationStatus REQUEST_EXPIRED =
      new CertificationStatus("REQUEST_EXPIRED");

  /** The aggregation service has not obtained a consensus reference time yet. */
  public static final CertificationStatus SERVICE_NOT_READY =
      new CertificationStatus("SERVICE_NOT_READY");

  /** A request for the state id is already certified (set by the aggregator). */
  public static final CertificationStatus STATE_ID_EXISTS =
      new CertificationStatus("STATE_ID_EXISTS");
  /** The aggregator confirms that this very request was submitted before. */
  public static final CertificationStatus ALREADY_SUBMITTED =
      new CertificationStatus("ALREADY_SUBMITTED");
  /**
   * A request for the state id is queued, possibly another one. Not an acceptance of this request.
   */
  public static final CertificationStatus STATE_ID_QUEUED =
      new CertificationStatus("STATE_ID_QUEUED");

  private static final CertificationStatus[] VALUES = {
      SUCCESS,
      STATE_ID_MISMATCH,
      SIGNATURE_VERIFICATION_FAILED,
      INVALID_SIGNATURE_FORMAT,
      INVALID_PUBLIC_KEY_FORMAT,
      INVALID_SOURCE_STATE_HASH_FORMAT,
      INVALID_TRANSACTION_HASH_FORMAT,
      UNSUPPORTED_ALGORITHM,
      INVALID_SHARD,
      REQUEST_EXPIRED,
      SERVICE_NOT_READY,
      STATE_ID_EXISTS,
      ALREADY_SUBMITTED,
      STATE_ID_QUEUED
  };

  private final String value;

  private CertificationStatus(String value) {
    this.value = value;
  }

  /**
   * Get string value of the status.
   *
   * @return string value
   */
  @JsonValue
  public String getValue() {
    return this.value;
  }

  /**
   * Resolve a status from its string value. Returns the registered singleton
   * for known statuses; constructs a new instance for any other (custom) value
   * returned by the aggregator.
   *
   * @param value string value
   * @return status
   */
  @JsonCreator
  public static CertificationStatus fromString(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Status value must not be null");
    }
    for (CertificationStatus status : CertificationStatus.VALUES) {
      if (status.value.equalsIgnoreCase(value)) {
        return status;
      }
    }
    return new CertificationStatus(value);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CertificationStatus)) {
      return false;
    }
    return this.value.equals(((CertificationStatus) o).value);
  }

  @Override
  public int hashCode() {
    return this.value.hashCode();
  }

  @Override
  public String toString() {
    return this.value;
  }
}
