package org.unicitylabs.sdk.serializer.cbor;

import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap.Entry;
import org.unicitylabs.sdk.util.BigIntegerConverter;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
  public static CborUnsignedLong decodeUnsignedInteger(byte[] data) {
    CborReader reader = new CborReader(data);
    CborUnsignedLong value = reader.readLength(CborMajorType.UNSIGNED_INTEGER);
    reader.assertExhausted();

    return value;
  }

  /**
   * Read byte string from CBOR bytes.
   *
   * @param data bytes
   * @return bytes
   */
  public static byte[] decodeByteString(byte[] data) {
    CborReader reader = new CborReader(data);
    byte[] result = reader.read(reader.readLength(CborMajorType.BYTE_STRING).asListSize());
    reader.assertExhausted();

    return result;
  }

  /**
   * Read a minimally encoded big-endian byte string from CBOR bytes as a non-negative big
   * integer. Rejects non-minimal encodings (leading zero byte); zero is encoded as an empty
   * byte string.
   *
   * @param data bytes
   * @return decoded integer
   */
  public static BigInteger decodeBigInteger(byte[] data) {
    return CborDeserializer.decodeBigInteger(data, null);
  }

  /**
   * Read a minimally encoded big-endian byte string from CBOR bytes as a non-negative big
   * integer, bounded to at most {@code maxByteLength} bytes.
   *
   * @param data bytes
   * @param maxByteLength maximum byte-string length, or {@code null} for no limit
   * @return decoded integer
   */
  public static BigInteger decodeBigInteger(byte[] data, Integer maxByteLength) {
    byte[] bytes = CborDeserializer.decodeByteString(data);
    if (bytes.length > 0 && bytes[0] == 0) {
      throw new CborSerializationException("Integer byte string must be minimally encoded.");
    }
    if (maxByteLength != null && bytes.length > maxByteLength) {
      throw new CborSerializationException(
              String.format("Integer byte string must be at most %d bytes.", maxByteLength));
    }

    return BigIntegerConverter.decode(bytes);
  }

  /**
   * Read text string from CBOR bytes.
   *
   * @param data bytes
   * @return text
   */
  public static String decodeTextString(byte[] data) {
    CborReader reader = new CborReader(data);
    byte[] bytes = reader.read(reader.readLength(CborMajorType.TEXT_STRING).asListSize());
    reader.assertExhausted();

    // Strictly UTF-8: malformed or unmappable input is an error, never replaced by U+FFFD and never
    // decoded with the platform default charset, so one byte string is one text and back.
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      throw new CborSerializationException("Text string is not valid UTF-8.", e);
    }
  }

  /**
   * Read elements as raw CBOR element array from CBOR bytes.
   *
   * @param data bytes
   * @return CBOR element array
   */
  public static List<byte[]> decodeArray(byte[] data) {
    CborReader reader = new CborReader(data);
    int length = reader.readLength(CborMajorType.ARRAY).asListSize();

    ArrayList<byte[]> result = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      result.add(reader.readRawCbor());
    }
    reader.assertExhausted();

    return result;
  }

  /**
   * Read elements as raw CBOR element array from CBOR bytes, refusing an array that declares more
   * than {@code maxElements} elements as soon as its header is read, before any element is read or
   * copied.
   *
   * @param data bytes
   * @param maxElements the most elements admitted
   * @return CBOR element array
   *
   * @throws CborSerializationException when the declared length exceeds {@code maxElements}
   */
  public static List<byte[]> decodeArrayAtMost(byte[] data, int maxElements) {
    CborReader reader = new CborReader(data);
    CborUnsignedLong declared = reader.readLength(CborMajorType.ARRAY);
    if (Long.compareUnsigned(declared.asLong(), maxElements) > 0) {
      throw new CborSerializationException(String.format(
              "Array of more than %d elements.", maxElements));
    }
    int length = declared.asListSize();

    ArrayList<byte[]> result = new ArrayList<>(Math.min(length, 1024));
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
    int length = reader.readLength(CborMajorType.MAP).asListSize();

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
   * Read elements as raw CBOR element map from CBOR bytes, refusing a map that declares more than
   * {@code maxPairs} pairs as soon as its header is read, before any pair is read or copied. Keys
   * must be in canonical order and unique, as in {@link #decodeMap(byte[])}.
   *
   * @param data bytes
   * @param maxPairs the most key-value pairs admitted
   * @return CBOR element map
   *
   * @throws CborSerializationException when the declared length exceeds {@code maxPairs}
   */
  public static Set<CborMap.Entry> decodeMapAtMost(byte[] data, int maxPairs) {
    CborReader reader = new CborReader(data);
    CborUnsignedLong declared = reader.readLength(CborMajorType.MAP);
    if (Long.compareUnsigned(declared.asLong(), maxPairs) > 0) {
      throw new CborSerializationException(String.format(
              "Map of more than %d pairs.", maxPairs));
    }
    // The pairs are read by the unbounded reader below only after the bound passed.
    return CborDeserializer.decodeMap(data);
  }

  /**
   * Read tag from CBOR bytes.
   *
   * @param data bytes
   * @return CBOR tag
   */
  public static CborTag decodeTag(byte[] data) {
    CborReader reader = new CborReader(data);
    long tag = reader.readLength(CborMajorType.TAG).asLong();
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
      if (length > this.data.length - this.position) {
        throw new CborSerializationException("Premature end of data.");
      }

      try {
        return Arrays.copyOfRange(this.data, this.position, this.position + length);
      } finally {
        this.position += length;
      }
    }

    public CborUnsignedLong readLength(CborMajorType majorType) {
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
        return new CborUnsignedLong(additionalInformation);
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

      return new CborUnsignedLong(t);
    }

    public byte[] readRawCbor() {
      int position = this.position;

      long remaining = 1;
      while (remaining > 0) {
        remaining--;

        if (this.position >= this.data.length) {
          throw new CborSerializationException("Premature end of data.");
        }

        CborMajorType majorType = CborMajorType.fromType(
                this.data[this.position] & CborDeserializer.MAJOR_TYPE_MASK);
        CborUnsignedLong length = this.readLength(majorType);
        switch (majorType) {
          case BYTE_STRING:
          case TEXT_STRING:
            this.read(length.asListSize());
            break;
          case ARRAY:
            // asInt bounds each count and every header consumes input, so the counter cannot
            // overflow; undersupplied counts fail with premature end of data as items are read.
            remaining += length.asListSize();
            break;
          case MAP:
            remaining += length.asListSize() * 2L;
            break;
          case TAG:
            remaining += 1;
            break;
          default:
            break;
        }
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
  public static class CborUnsignedLong {

    private final long value;

    private CborUnsignedLong(long value) {
      this.value = value;
    }

    /**
     * Get the raw unsigned value as a {@code long}. Values at or above {@code 2^63} are returned
     * as a negative long with the same bit pattern; use {@link Long#compareUnsigned} to compare
     * them.
     *
     * @return value
     */
    public long asLong() {
      return this.value;
    }

    /**
     * Get the value as an unsigned 32-bit integer, throwing if it exceeds {@code 0xFFFFFFFF}.
     * Values above {@link Integer#MAX_VALUE} are returned with the same bit pattern (a negative
     * {@code int}); mask with {@code & 0xFFFFFFFFL} to recover the unsigned value.
     *
     * @return value
     */
    public int asInt() {
      if (Long.compareUnsigned(this.value, 0xFFFFFFFFL) > 0) {
        throw new CborSerializationException("Value too large");
      }
      return (int) this.value;
    }

    /**
     * Get the value as an unsigned 16-bit integer, throwing if it exceeds {@code 0xFFFF}. Values
     * above {@link Short#MAX_VALUE} are returned with the same bit pattern (a negative
     * {@code short}); mask with {@code & 0xFFFF} to recover the unsigned value.
     *
     * @return value
     */
    public short asShort() {
      if (Long.compareUnsigned(this.value, 0xFFFFL) > 0) {
        throw new CborSerializationException("Value too large");
      }

      return (short) this.value;
    }

    /**
     * Get the value as an unsigned 8-bit integer, throwing if it exceeds {@code 0xFF}. Values
     * above {@link Byte#MAX_VALUE} are returned with the same bit pattern (a negative
     * {@code byte}); mask with {@code & 0xFF} to recover the unsigned value.
     *
     * @return value
     */
    public byte asByte() {
      if (Long.compareUnsigned(this.value, 0xFFL) > 0) {
        throw new CborSerializationException("Value too large");
      }

      return (byte) this.value;
    }

    /**
     * Get the value as a non-negative {@code int} bounded to {@link Integer#MAX_VALUE}, suitable
     * for a collection size, byte-string length or index. Throws if the value would not fit in a
     * valid Java array size.
     *
     * @return value
     */
    public int asListSize() {
      if (Long.compareUnsigned(this.value, Integer.MAX_VALUE) > 0) {
        throw new CborSerializationException("Value too large");
      }

      return (int) this.value;
    }
  }
}
