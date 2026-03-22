package org.unicitylabs.sdk.util;

/**
 * Utility class for converting between byte arrays and hexadecimal strings.
 */
public class HexConverter {

  private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

  private HexConverter() {
  }

  /**
   * Convert byte array to hex.
   *
   * @param data byte array
   * @return hex string
   */
  public static String encode(byte[] data) {
    char[] hexChars = new char[data.length * 2];
    for (int j = 0; j < data.length; j++) {
      int v = data[j] & 0xFF;
      hexChars[j * 2] = HEX_ARRAY[v >>> 4];
      hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
    }
    return new String(hexChars);
  }

  /**
   * Convert hex string to bytes.
   *
   * @param value hex string
   * @return byte array
   */
  public static byte[] decode(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Input is null");
    }
    if (value.length() % 2 != 0) {
      throw new IllegalArgumentException("Hex string must have even length");
    }

    value = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;

    int len = value.length();
    byte[] data = new byte[len / 2];

    for (int i = 0; i < len; i += 2) {
      int hi = Character.digit(value.charAt(i), 16);
      int lo = Character.digit(value.charAt(i + 1), 16);
      if (hi == -1 || lo == -1) {
        throw new IllegalArgumentException("Invalid hex character at position " + i);
      }
      data[i / 2] = (byte) ((hi << 4) + lo);
    }
    return data;
  }
}