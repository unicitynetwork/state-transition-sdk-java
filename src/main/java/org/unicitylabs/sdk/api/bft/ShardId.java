package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;

import java.util.Arrays;
import java.util.Objects;

public class ShardId {

  private final byte[] bits;
  private final int length;

  private ShardId(byte[] bits, int length) {
    this.bits = Arrays.copyOf(bits, bits.length);
    this.length = length;
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
