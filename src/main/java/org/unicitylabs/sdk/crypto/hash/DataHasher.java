package org.unicitylabs.sdk.crypto.hash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * DataHasher is a utility class for hashing data using a specified hash algorithm. It provides methods to update the
 * hash with data and to retrieve the final hash.
 */
public class DataHasher {

  private final HashAlgorithm algorithm;
  private final MessageDigest messageDigest;

  /**
   * Creates a DataHasher instance with the specified hash algorithm.
   *
   * @param algorithm the hash algorithm to use
   */
  public DataHasher(HashAlgorithm algorithm) {
    this.algorithm = algorithm;
    try {
      this.messageDigest = MessageDigest.getInstance(algorithm.getAlgorithm());
    } catch (NoSuchAlgorithmException e) {
      throw new UnsupportedHashAlgorithmException(algorithm);
    }
  }

  /**
   * Returns the hash algorithm used by this DataHasher.
   *
   * @return the hash algorithm
   */
  public HashAlgorithm getAlgorithm() {
    return algorithm;
  }

  /**
   * Updates the digest with the given byte array.
   *
   * @param data the byte array
   * @return this DataHasher instance for method chaining
   */
  public DataHasher update(byte[] data) {
    this.messageDigest.update(data);
    return this;
  }

  /**
   * Gets the final hash digest.
   *
   * @return the final hash as a DataHash object
   */
  public DataHash digest() {
    return new DataHash(this.algorithm, this.messageDigest.digest());
  }
}