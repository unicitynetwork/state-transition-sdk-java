package org.unicitylabs.sdk.crypto.hash;

/**
 * Hash algorithm representation.
 */
public enum HashAlgorithm {
  /**
   * SHA2-256 hash algorithm.
   */
  SHA256(0, "SHA-256", 32),
  /**
   * SHA2-224 hash algorithm.
   */
  SHA224(1, "SHA-224", 28),
  /**
   * SHA2-384 hash algorithm.
   */
  SHA384(2, "SHA-384", 48),
  /**
   * SHA2-512 hash algorithm.
   */
  SHA512(3, "SHA-512", 64),
  /**
   * RIPEMD160 hash algorithm.
   */
  RIPEMD160(4, "RIPEMD160", 20);

  private final int value;
  private final String algorithm;
  private final int length;

  HashAlgorithm(int value, String algorithm, int length) {
    this.value = value;
    this.algorithm = algorithm;
    this.length = length;
  }

  /**
   * Hash algorithm value in imprint.
   *
   * @return value
   */
  public int getValue() {
    return value;
  }

  /**
   * Hash algorithm string representation.
   *
   * @return algorithm
   */
  public String getAlgorithm() {
    return this.algorithm;
  }

  /**
   * Hash algorithm length in bytes.
   *
   * @return length
   */
  public int getLength() {
    return this.length;
  }

  /**
   * Get HashAlgorithm from its numeric value.
   *
   * @param value The numeric value
   * @return The corresponding HashAlgorithm
   * @throws IllegalArgumentException if value is not valid
   */
  public static HashAlgorithm fromValue(int value) {
    for (HashAlgorithm algorithm : HashAlgorithm.values()) {
      if (algorithm.getValue() == value) {
        return algorithm;
      }
    }
    throw new IllegalArgumentException("Invalid HashAlgorithm value: " + value);
  }
}