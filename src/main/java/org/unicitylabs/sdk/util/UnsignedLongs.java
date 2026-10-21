package org.unicitylabs.sdk.util;

import java.math.BigInteger;

/**
 * Helpers for CBOR unsigned integers that are carried as raw {@code long} bits (see
 * {@code CborUnsignedLong}). Rounds, epochs and similar protocol numbers use the full range
 * 0..2^64-1, so signed comparison, signed decimal conversion and conversion through {@code double}
 * are all wrong for values at or above 2^63. Every comparison, rendering and range check of such a
 * number goes through this class.
 */
public final class UnsignedLongs {

  private static final BigInteger TWO_POW_64 = BigInteger.ONE.shiftLeft(64);

  private UnsignedLongs() {
  }

  /**
   * Compares two unsigned values.
   *
   * @param a first value (unsigned bits)
   * @param b second value (unsigned bits)
   * @return negative, zero or positive as {@code a} is less than, equal to or greater than {@code b}
   */
  public static int compare(long a, long b) {
    return Long.compareUnsigned(a, b);
  }

  /**
   * Tells if {@code a} is less than {@code b} as unsigned values.
   *
   * @param a first value (unsigned bits)
   * @param b second value (unsigned bits)
   * @return true if {@code a < b}
   */
  public static boolean lessThan(long a, long b) {
    return Long.compareUnsigned(a, b) < 0;
  }

  /**
   * Converts unsigned bits to a decimal string.
   *
   * @param value unsigned bits
   * @return decimal text of the unsigned value
   */
  public static String toDecimal(long value) {
    return Long.toUnsignedString(value);
  }

  /**
   * Converts unsigned bits to a non-negative {@link BigInteger}.
   *
   * @param value unsigned bits
   * @return the unsigned value
   */
  public static BigInteger toBigInteger(long value) {
    BigInteger signed = BigInteger.valueOf(value);
    return value >= 0 ? signed : signed.add(TWO_POW_64);
  }

  /**
   * Converts a non-negative {@link BigInteger} below 2^64 to unsigned bits.
   *
   * @param value value in 0..2^64-1
   * @return unsigned bits
   * @throws IllegalArgumentException if the value is out of range
   */
  public static long fromBigInteger(BigInteger value) {
    if (value.signum() < 0 || value.compareTo(TWO_POW_64) >= 0) {
      throw new IllegalArgumentException("Value is out of the unsigned 64-bit range.");
    }
    return value.longValue();
  }

  /**
   * Parses an unsigned decimal string.
   *
   * @param decimal decimal text in 0..2^64-1
   * @return unsigned bits
   * @throws NumberFormatException if the text is not an unsigned 64-bit decimal
   */
  public static long parseDecimal(String decimal) {
    return Long.parseUnsignedLong(decimal);
  }
}
