package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;

import java.util.Arrays;
import java.util.Objects;

/**
 * Shard identifier: a bit string of at most 256 bits in a view, encoded with a trailing end marker
 * bit. Equality is by bits and length.
 */
public class ShardId {

  private static final ShardId EMPTY = new ShardId(new byte[0], 0);

  private final byte[] bits;
  private final int length;

  private ShardId(byte[] bits, int length) {
    this.bits = Arrays.copyOf(bits, bits.length);
    this.length = length;
  }

  /**
   * The empty shard id, the only shard of an unsharded partition (encoded {@code 80}).
   *
   * @return the empty shard id
   */
  public static ShardId empty() {
    return EMPTY;
  }

  /**
   * Builds a shard id from its bit string, for example {@code "01"}; the empty string is
   * {@link #empty()}.
   *
   * @param bitString characters {@code 0} and {@code 1}
   * @return shard id
   * @throws IllegalArgumentException if the string holds another character
   */
  public static ShardId fromBitString(String bitString) {
    if (bitString.isEmpty()) {
      return EMPTY;
    }
    byte[] bytes = new byte[(bitString.length() + 7) / 8];
    for (int i = 0; i < bitString.length(); i++) {
      char c = bitString.charAt(i);
      if (c == '1') {
        bytes[i / 8] |= (byte) (0x80 >> (i % 8));
      } else if (c != '0') {
        throw new IllegalArgumentException("A shard id bit string holds only 0 and 1.");
      }
    }
    return new ShardId(bytes, bitString.length());
  }

  /**
   * Whether this is the empty shard id.
   *
   * @return true for the empty shard id
   */
  public boolean isEmpty() {
    return this.length == 0;
  }

  public byte[] getBits() {
    return Arrays.copyOf(this.bits, this.bits.length);
  }

  public int getLength() {
    return this.length;
  }

  public static ShardId decode(byte[] data) {
    if (data.length == 0) {
      throw new CborSerializationException("Invalid ShardId encoding: empty input");
    }

    int lastByte = data[data.length - 1] & 0xff;

    for (int i = 8; i > 0; i--) {
      if ((lastByte & 1) == 1) {
        if (i == 1) {
          return new ShardId(
                  Arrays.copyOfRange(data, 0, data.length - 1),
                  (data.length - 1) * 8
          );
        }

        byte[] bits = Arrays.copyOfRange(data, 0, data.length);
        bits[data.length - 1] = (byte) (((lastByte >> 1) << (8 - i + 1)) & 0xff);
        return new ShardId(bits, (data.length - 1) * 8 + i - 1);
      }

      lastByte >>= 1;
    }

    throw new CborSerializationException(
            "Invalid ShardId encoding: last byte doesnt contain end marker");
  }

  public byte[] encode() {
    int byteCount = this.length / 8;
    int bitCount = this.length % 8;
    byte[] result = new byte[byteCount + 1];
    System.arraycopy(this.bits, 0, result, 0, byteCount);
    if (bitCount == 0) {
      result[byteCount] = (byte) 0b10000000;
    } else {
      int v = this.bits[byteCount] & (~(0xff >> bitCount) & 0xff);
      result[byteCount] = (byte) ((v | (1 << (7 - bitCount))) & 0xff);
    }
    return result;
  }

  public int getBit(int index) {
    if (index < 0 || index >= this.length) {
      throw new IndexOutOfBoundsException("ShardId bit index out of bounds");
    }
    return ((this.bits[index / 8] & 0xff) >> (7 - (index % 8))) & 1;
  }

  /**
   * Tells whether this shard id is a bit prefix of another shard id (every id is a prefix of
   * itself, the empty id of all).
   *
   * @param other the longer or equal id
   * @return true if the first {@code getLength()} bits of {@code other} equal this id
   */
  public boolean isPrefixOf(ShardId other) {
    return this.length <= other.length && this.isPrefixOf(other.bits);
  }

  /**
   * Compares two shard ids in binary tree left-to-right order: lexicographic over the bits with
   * {@code 0} before {@code 1}, a prefix before the longer ids it prefixes.
   *
   * @param a first id
   * @param b second id
   * @return negative, zero or positive as {@code a} sorts before, equal to or after {@code b}
   */
  public static int compareTreeOrder(ShardId a, ShardId b) {
    int common = Math.min(a.length, b.length);
    for (int i = 0; i < common / 8; i++) {
      int diff = (a.bits[i] & 0xff) - (b.bits[i] & 0xff);
      if (diff != 0) {
        return diff;
      }
    }
    int rest = common % 8;
    if (rest > 0) {
      int mask = 0xff & (0xff << (8 - rest));
      int diff = (a.bits[common / 8] & mask) - (b.bits[common / 8] & mask);
      if (diff != 0) {
        return diff;
      }
    }
    return Integer.compare(a.length, b.length);
  }

  public boolean isPrefixOf(byte[] data) {
    if (data.length * 8 < this.length) {
      return false;
    }

    int fullBytes = this.length / 8;
    int remainingBits = this.length % 8;

    for (int i = 0; i < fullBytes; i++) {
      if (this.bits[i] != data[i]) {
        return false;
      }
    }

    if (remainingBits > 0) {
      int mask = 0xff & (0xff << (8 - remainingBits));
      return (this.bits[fullBytes] & mask) == (data[fullBytes] & mask);
    }

    return true;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ShardId)) {
      return false;
    }
    ShardId that = (ShardId) o;
    return this.length == that.length && Arrays.equals(this.bits, that.bits);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.length, Arrays.hashCode(this.bits));
  }

  @Override
  public String toString() {
    int fullBytes = this.length / 8;
    int remainingBits = this.length % 8;
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < fullBytes; i++) {
      String bin = Integer.toBinaryString(this.bits[i] & 0xff);
      result.append("00000000", 0, 8 - bin.length()).append(bin);
    }
    if (remainingBits > 0) {
      String bin = Integer.toBinaryString(this.bits[fullBytes] & 0xff);
      String padded = "00000000".substring(0, 8 - bin.length()) + bin;
      result.append(padded, 0, remainingBits);
    }
    return result.toString();
  }
}
