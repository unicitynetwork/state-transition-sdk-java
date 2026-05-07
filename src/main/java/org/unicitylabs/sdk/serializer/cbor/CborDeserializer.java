package org.unicitylabs.sdk.serializer.cbor;

import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap.Entry;

import java.util.*;
import java.util.function.Function;

/**
 * CBOR deserialization utilities.
 */
public class CborDeserializer {

  private static final byte MAJOR_TYPE_MASK = (byte) 0b11100000;
  private static final byte ADDITIONAL_INFORMATION_MASK = (byte) 0b00011111;

  private CborDeserializer() {
  }

  /**
   * Read optional value from CBOR bytes.
   *
   * @param data   bytes
   * @param decoder parse method
   * @param <T>    parsed value type
   * @return parsed value
   */
  public static <T> T decodeNullable(byte[] data, Function<byte[], T> decoder) {
    CborReader reader = new CborReader(data);
    byte[] cbor = reader.readRawCbor();
    reader.assertExhausted();

    if (cbor.length == 1 && cbor[0] == (byte) 0xf6) {
      return null;
    }

    return decoder.apply(cbor);
  }

  /**
   * Read unsigned integer from CBOR bytes.
   *
   * @param data bytes
   * @return unsigned number
   */
  public static CborNumber decodeUnsignedInteger(byte[] data) {
    CborReader reader = new CborReader(data);
    long value = reader.readLength(CborMajorType.UNSIGNED_INTEGER);
    reader.assertExhausted();

    return new CborNumber(value);
  }

  /**
   * Read byte string from CBOR bytes.
   *
   * @param data bytes
   * @return bytes
   */
  public static byte[] decodeByteString(byte[] data) {
    CborReader reader = new CborReader(data);
    byte[] result = reader.read((int) reader.readLength(CborMajorType.BYTE_STRING));
    reader.assertExhausted();

    return result;
  }

  /**
   * Read text string from CBOR bytes.
   *
   * @param data bytes
   * @return text
   */
  public static String decodeTextString(byte[] data) {
    CborReader reader = new CborReader(data);
    byte[] bytes = reader.read((int) reader.readLength(CborMajorType.TEXT_STRING));
    reader.assertExhausted();

    return new String(bytes);
  }

  /**
   * Read elements as raw CBOR element array from CBOR bytes.
   *
   * @param data bytes
   * @return CBOR element array
   */
  public static List<byte[]> decodeArray(byte[] data) {
    CborReader reader = new CborReader(data);
    long length = reader.readLength(CborMajorType.ARRAY);

    ArrayList<byte[]> result = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      result.add(reader.readRawCbor());
    }
    reader.assertExhausted();

    return result;
  }

  /**
   * Read a fixed-size CBOR array from bytes. Throws when the encoded array length does not match
   * the expected length.
   *
   * @param data bytes
   * @param expectedLength expected number of array elements
   * @return CBOR element array
   *
   * @throws CborSerializationException when the array length differs from {@code expectedLength}
   */
  public static List<byte[]> decodeArray(byte[] data, long expectedLength) {
    List<byte[]> result = decodeArray(data);
    if (result.size() != expectedLength) {
      throw new CborSerializationException(
              String.format("Expected array of %d elements, got %d", expectedLength, result.size()));
    }
    return result;
  }

  /**
   * Read elements as raw CBOR element map from CBOR bytes.
   *
   * @param data bytes
   * @return CBOR element map
   */
  public static Set<CborMap.Entry> decodeMap(byte[] data) {
    CborReader reader = new CborReader(data);
    long length = (int) reader.readLength(CborMajorType.MAP);

    Set<Entry> result = new LinkedHashSet<>();
    Entry previous = null;
    for (int i = 0; i < length; i++) {
      Entry entry = new CborMap.Entry(reader.readRawCbor(), reader.readRawCbor());

      if (previous != null) {
        int comparison = CborMap.compareEntries(previous, entry);
        if (comparison == 0) {
          throw new CborSerializationException("Duplicate map key found.");
        }
        if (comparison > 0) {
          throw new CborSerializationException("Map keys are not in canonical order.");
        }
      }
      result.add(entry);
      previous = entry;
    }
    reader.assertExhausted();

    return result;
  }

  /**
   * Read tag from CBOR bytes.
   *
   * @param data bytes
   * @return CBOR tag
   */
  public static CborTag decodeTag(byte[] data) {
    CborReader reader = new CborReader(data);
    long tag = (int) reader.readLength(CborMajorType.TAG);
    byte[] inner = reader.readRawCbor();
    reader.assertExhausted();

    return new CborTag(tag, inner);
  }

  /**
   * Read boolean from CBOR bytes.
   *
   * @param data bytes
   * @return boolean
   */
  public static boolean decodeBoolean(byte[] data) {
    CborReader reader = new CborReader(data);
    byte[] cbor = reader.readRawCbor();
    reader.assertExhausted();

    if (cbor.length == 1 && cbor[0] == (byte) 0xf5) {
      return true;
    }
    if (cbor.length == 1 && cbor[0] == (byte) 0xf4) {
      return false;
    }
    throw new CborSerializationException("Type mismatch, expected boolean.");
  }

  private static class CborReader {

    final byte[] data;
    int position = 0;

    CborReader(byte[] data) {
      Objects.requireNonNull(data, "Input byte array cannot be null.");

      this.data = data;
    }

    public void assertExhausted() {
      if (this.position != this.data.length) {
        throw new CborSerializationException(String.format(
                "Expected end of data: %d byte(s) remaining at position %d.",
                this.data.length - this.position, this.position));
      }
    }

    public byte readByte() {
      if (this.position >= this.data.length) {
        throw new CborSerializationException("Premature end of data.");
      }

      return this.data[this.position++];
    }

    public byte[] read(int length) {
      try {
        if ((this.position + length) > this.data.length) {
          throw new CborSerializationException("Premature end of data.");
        }

        return Arrays.copyOfRange(this.data, this.position, this.position + length);
      } finally {
        this.position += length;
      }
    }

    public long readLength(CborMajorType majorType) {
      byte initialByte = this.readByte();

      CborMajorType parsedMajorType = CborMajorType.fromType(
              initialByte & CborDeserializer.MAJOR_TYPE_MASK);
      if (parsedMajorType != majorType) {
        throw new CborSerializationException(String.format(
                "Major type mismatch: expected %s, got %s.", majorType, parsedMajorType));
      }

      byte additionalInformation = (byte) (initialByte
              & CborDeserializer.ADDITIONAL_INFORMATION_MASK);
      if (Byte.compareUnsigned(additionalInformation, (byte) 24) < 0) {
        return additionalInformation;
      }

      switch (majorType) {
        case MAP:
        case ARRAY:
        case BYTE_STRING:
        case TEXT_STRING:
          if (Byte.compareUnsigned(additionalInformation, (byte) 31) == 0) {
            throw new CborSerializationException(String.format(
                    "Indefinite-length encoding not allowed in canonical CBOR (major type %s).",
                    majorType));
          }
          break;
        default:
      }

      if (Byte.compareUnsigned(additionalInformation, (byte) 27) > 0) {
        throw new CborSerializationException(String.format(
                "Reserved additional information %d for major type %s.",
                additionalInformation, majorType));
      }

      long t = 0;
      int length = (int) Math.pow(2, additionalInformation - 24);
      for (int i = 0; i < length; ++i) {
        t = (t << 8) | this.readByte() & 0xFF;
      }

      long threshold = length == 1 ? 24L : 1L << (length * 4);
      if (Long.compareUnsigned(t, threshold) < 0) {
        throw new CborSerializationException(String.format(
                "Byte length %d is not canonical for value %s.",
                length, Long.toUnsignedString(t)));
      }

      return t;
    }

    public byte[] readRawCbor() {
      if (this.position >= this.data.length) {
        throw new CborSerializationException("Premature end of data.");
      }

      CborMajorType majorType = CborMajorType.fromType(
              this.data[this.position] & CborDeserializer.MAJOR_TYPE_MASK);
      int position = this.position;
      int length = (int) this.readLength(majorType);
      switch (majorType) {
        case BYTE_STRING:
        case TEXT_STRING:
          this.read(length);
          break;
        case ARRAY:
          for (int i = 0; i < length; i++) {
            this.readRawCbor();
          }
          break;
        case MAP:
          for (int i = 0; i < length; i++) {
            this.readRawCbor();
            this.readRawCbor();
          }
          break;
        case TAG:
          this.readRawCbor();
          break;
        default:
          break;
      }

      return Arrays.copyOfRange(this.data, position, this.position);
    }
  }

  /**
   * CBOR Tag implementation.
   */
  public static class CborTag {

    private final long tag;
    private final byte[] data;

    private CborTag(long tag, byte[] data) {
      this.tag = tag;
      this.data = data;
    }

    /**
     * Get tag number.
     *
     * @return tag
     */
    public long getTag() {
      return this.tag;
    }

    /**
     * Get data associated with tag.
     *
     * @return tag data
     */
    public byte[] getData() {
      return Arrays.copyOf(this.data, this.data.length);
    }
  }

  /**
   * CBOR number implementation.
   */
  public static class CborNumber {

    private final long value;

    private CborNumber(long value) {
      this.value = value;
    }

    /**
     * Get number as long.
     *
     * @return number
     */
    public long asLong() {
      return this.value;
    }

    /**
     * Get number as int, throw error if does not fit.
     *
     * @return number
     */
    public int asInt() {
      if (Long.compareUnsigned(this.value, 0xFFFFFFFFL) > 0) {
        throw new ArithmeticException("Value too large");
      }
      return (int) this.value;
    }

    /**
     * Get number as byte, throw error if does not fit.
     *
     * @return number
     */
    public byte asByte() {
      if (Long.compareUnsigned(this.value, 0xFFL) > 0) {
        throw new ArithmeticException("Value too large");
      }

      return (byte) this.value;
    }

    /**
     * Get number as short, throw error if does not fit.
     *
     * @return number
     */
    public short asShort() {
      if (Long.compareUnsigned(this.value, 0xFFFFL) > 0) {
        throw new ArithmeticException("Value too large");
      }

      return (short) this.value;
    }
  }
}
