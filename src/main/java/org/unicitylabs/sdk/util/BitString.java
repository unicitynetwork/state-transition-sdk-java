package org.unicitylabs.sdk.util;

import java.math.BigInteger;
import org.unicitylabs.sdk.api.StateId;

/**
 * Represents a bit string as a BigInteger. This class is used to ensure that leading zero bits are retained when
 * converting between byte arrays and BigInteger.
 */
public class BitString {

  private final BigInteger value;

  /**
   * Creates a BitString from a byte array.
   *
   * @param data The input data to convert into a BitString.
   */
  public BitString(byte[] data) {
    byte[] dataWithPrefix = new byte[data.length + 1];
    dataWithPrefix[0] = 1;
    System.arraycopy(data, 0, dataWithPrefix, 1, data.length);
    this.value = new BigInteger(1, dataWithPrefix);
  }

  /**
   * Converts BitString to BigInteger by adding a leading byte 1 to input byte array. This is to ensure that the
   * BigInteger will retain the leading zero bits.
   *
   * @return The BigInteger representation of the bit string
   */
  public BigInteger toBigInteger() {
    return value;
  }

  /**
   * Converts bit string to string.
   *
   * @return The string representation of the bit string in binary format
   */
  @Override
  public String toString() {
    String binary = value.toString(2);
    // Remove the leading '1' bit we added
    if (binary.length() > 1 && binary.charAt(0) == '1') {
      return binary.substring(1);
    }
    return binary;
  }
}