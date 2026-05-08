package org.unicitylabs.sdk.util;

/**
 * Long converter to bytes.
 */
public class LongConverter {

  private LongConverter() {
  }

  /**
   * Encode a non-negative long as minimum-length unsigned big-endian bytes, with a minimum length
   * of one byte. Zero is encoded as {@code [0x00]}, not the empty array.
   *
   * @param value non-negative long
   * @return bytes
   */
  public static byte[] encode(long value) {
    if (value < 0) {
      throw new IllegalArgumentException("value must be non-negative");
    }
    if (value == 0) {
      return new byte[]{0x00};
    }
    int length = (64 - Long.numberOfLeadingZeros(value) + 7) / 8;
    byte[] result = new byte[length];
    for (int i = length - 1; i >= 0; i--) {
      result[i] = (byte) (value & 0xffL);
      value >>>= 8;
    }
    return result;
  }
}
