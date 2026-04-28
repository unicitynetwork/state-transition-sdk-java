package org.unicitylabs.sdk.serializer.cbor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * CBOR serialization utilities.
 */
public class CborSerializer {

  private CborSerializer() {
  }

  /**
   * Encode value to CBOR, if null encode null bytes.
   *
   * @param data    value to be encoded
   * @param encoder encode method
   * @param <T>     value type
   * @return bytes
   */
  public static <T> byte[] encodeNullable(T data, Function<T, byte[]> encoder) {
    if (data == null) {
      return new byte[]{(byte) 0xf6};
    }
    return encoder.apply(data);
  }

  /**
   * Encode unsigned integer to CBOR bytes.
   *
   * @param input unsigned integer
   * @return bytes
   */
  public static byte[] encodeUnsignedInteger(long input) {
    if (Long.compareUnsigned(input, 24) < 0) {
      return new byte[]{(byte) (CborMajorType.UNSIGNED_INTEGER.getType() | input)};
    }

    byte[] bytes = CborSerializer.getUnsignedLongAsPaddedBytes(input);
    byte[] result = new byte[1 + bytes.length];
    System.arraycopy(bytes, 0, result, 1, bytes.length);
    result[0] = (byte) (CborMajorType.UNSIGNED_INTEGER.getType()
            | CborSerializer.getAdditionalInformationBits(bytes.length));

    return result;
  }

  /**
   * Encode byte string to CBOR bytes.
   *
   * @param input bytes
   * @return bytes
   */
  public static byte[] encodeByteString(byte[] input) {
    if (input == null) {
      throw new CborSerializationException("Input byte array cannot be null.");
    }

    return CborSerializer.encodeRawArray(input, input.length, CborMajorType.BYTE_STRING);
  }

  /**
   * Encode text string as CBOR bytes.
   *
   * @param input text
   * @return bytes
   */
  public static byte[] encodeTextString(String input) {
    if (input == null) {
      throw new CborSerializationException("Input string cannot be null.");
    }

    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    return CborSerializer.encodeRawArray(bytes, bytes.length, CborMajorType.TEXT_STRING);
  }

  /**
   * Encode array of CBOR elements to single CBOR byte array.
   *
   * @param input elements
   * @return bytes
   */
  public static byte[] encodeArray(byte[]... input) {
    if (input == null) {
      throw new CborSerializationException("Input byte array list cannot be null.");
    }

    int length = 0;
    for (byte[] bytes : input) {
      length += bytes.length;
    }

    byte[] data = new byte[length];
    length = 0;
    for (byte[] bytes : input) {
      System.arraycopy(bytes, 0, data, length, bytes.length);
      length += bytes.length;
    }

    return CborSerializer.encodeRawArray(data, input.length, CborMajorType.ARRAY);
  }

  /**
   * Encode map of CBOR elements to single CBOR byte array.
   *
   * @param input map with hex converted keys
   * @return CBOR representation of the map
   */
  public static byte[] encodeMap(CborMap input) {
    if (input == null) {
      throw new CborSerializationException("Input set for map entry cannot be null.");
    }

    int length = 0;
    for (CborMap.Entry entry : input.entries) {
      length += entry.key.length + entry.value.length;
    }

    byte[] data = new byte[length];
    length = 0;
    for (CborMap.Entry entry : input.entries) {
      System.arraycopy(entry.key, 0, data, length, entry.key.length);
      length += entry.key.length;
      System.arraycopy(entry.value, 0, data, length, entry.value.length);
      length += entry.value.length;
    }

    return CborSerializer.encodeRawArray(data, input.entries.size(), CborMajorType.MAP);
  }

  /**
   * Encode CBOR tag with CBOR encoded element to single CBOR byte array.
   *
   * @param tag   CBOR tag
   * @param input element
   * @return bytes
   */
  public static byte[] encodeTag(long tag, byte[] input) {
    if (Long.compareUnsigned(tag, 24) < 0) {
      byte[] result = new byte[1 + input.length];
      result[0] = (byte) (CborMajorType.TAG.getType() | tag);
      System.arraycopy(input, 0, result, 1, input.length);

      return result;
    }

    byte[] bytes = CborSerializer.getUnsignedLongAsPaddedBytes(tag);
    byte[] result = new byte[1 + bytes.length + input.length];
    result[0] = (byte) (CborMajorType.TAG.getType()
            | CborSerializer.getAdditionalInformationBits(bytes.length));
    System.arraycopy(bytes, 0, result, 1, bytes.length);
    System.arraycopy(input, 0, result, 1 + bytes.length, input.length);

    return result;
  }

  /**
   * Encode boolean to CBOR bytes.
   *
   * @param input boolean
   * @return bytes
   */
  public static byte[] encodeBoolean(boolean input) {
    return new byte[]{(byte) (input ? 0xf5 : 0xf4)};
  }

  /**
   * Encode null to CBOR bytes.
   *
   * @return bytes
   */
  public static byte[] encodeNull() {
    return new byte[]{(byte) 0xf6};
  }

  private static byte[] encodeRawArray(byte[] input, int length, CborMajorType type) {
    if (length < 24) {
      byte[] result = new byte[1 + input.length];
      result[0] = (byte) (type.getType() | length);
      System.arraycopy(input, 0, result, 1, input.length);

      return result;
    }

    byte[] lengthBytes = CborSerializer.getUnsignedLongAsPaddedBytes(length);
    byte[] result = new byte[1 + lengthBytes.length + input.length];
    result[0] = (byte) (type.getType()
            | CborSerializer.getAdditionalInformationBits(lengthBytes.length));
    System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.length);
    System.arraycopy(input, 0, result, 1 + lengthBytes.length, input.length);

    return result;
  }

  private static int getAdditionalInformationBits(int length) {
    return 24 + (int) Math.ceil(Math.log(length) / Math.log(2));
  }

  private static byte[] getUnsignedLongAsPaddedBytes(long input) {
    int length = 0;
    for (long t = input; Long.compareUnsigned(t, 0) > 0; t = t >>> 8) {
      length++;
    }

    ByteBuffer buffer = ByteBuffer
            .allocate((int) Math.pow(2, (int) Math.ceil(Math.log(length) / Math.log(2))))
            .order(ByteOrder.BIG_ENDIAN);
    if (length <= 1) {
      buffer.put((byte) input);
    } else if (length <= 2) {
      buffer.putShort((short) input);
    } else if (length <= 4) {
      buffer.putInt((int) input);
    } else {
      buffer.putLong(input);
    }

    return buffer.array();
  }

  /**
   * CBOR map to order elements for canonicalization.
   */
  public static final class CborMap {

    private final ArrayList<Entry> entries;

    /**
     * Create map from set of CBOR elements.
     *
     * @param entries elements
     */
    public CborMap(Set<Entry> entries) {
      this.entries = new ArrayList<>(entries);
      this.entries.sort((a, b) -> {
        if (a.key.length != b.key.length) {
          return a.key.length - b.key.length;
        }

        for (int i = 0; i < a.key.length; i++) {
          if (a.key[i] != b.key[i]) {
            return a.key[i] - b.key[i];
          }
        }

        return 0;
      });
    }

    /**
     * Get CBOR element list.
     *
     * @return element list
     */
    public List<Entry> getEntries() {
      return List.copyOf(this.entries);
    }

    /**
     * CBOR entry for map.
     */
    public static final class Entry {

      private final byte[] key;
      private final byte[] value;

      /**
       * Create new CBOR element map entry.
       *
       * @param key   CBOR bytes
       * @param value CBOR bytes
       */
      public Entry(byte[] key, byte[] value) {
        Objects.requireNonNull(key, "Key cannot be null.");
        Objects.requireNonNull(value, "Value cannot be null.");

        this.key = Arrays.copyOf(key, key.length);
        this.value = Arrays.copyOf(value, value.length);
      }

      /**
       * Get entry key CBOR bytes element.
       *
       * @return bytes
       */
      public byte[] getKey() {
        return Arrays.copyOf(this.key, this.key.length);
      }

      /**
       * Get entry value CBOR bytes element.
       *
       * @return bytes
       */
      public byte[] getValue() {
        return Arrays.copyOf(this.value, this.value.length);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof Entry)) {
          return false;
        }
        Entry other = (Entry) o;
        return Arrays.equals(this.key, other.key);
      }

      @Override
      public int hashCode() {
        return Arrays.hashCode(this.key);
      }
    }
  }
}
