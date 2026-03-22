package org.unicitylabs.sdk.transaction;

import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

public class Address {

  private final byte[] bytes;

  private Address(byte[] bytes) {
    this.bytes = bytes;
  }

  public byte[] getBytes() {
    return Arrays.copyOf(this.bytes, this.bytes.length);
  }

  public static Address fromBytes(byte[] bytes) {
    if (bytes == null || bytes.length != 32) {
      throw new IllegalArgumentException("Invalid address length");
    }

    return new Address(Arrays.copyOf(bytes, bytes.length));
  }

  public static Address fromCbor(byte[] bytes) {
    return new Address(CborDeserializer.decodeByteString(bytes));
  }

  public static Address fromPredicate(Predicate predicate) {
    DataHash hash = new DataHasher(HashAlgorithm.SHA256).update(
        EncodedPredicate.fromPredicate(predicate).toCbor()).digest();
    return new Address(hash.getData());
  }

  public byte[] toCbor() {
    return CborSerializer.encodeByteString(this.bytes);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Address)) {
      return false;
    }
    Address address = (Address) o;
    return Arrays.equals(this.bytes, address.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(this.bytes);
  }

  public String toString() {
    return String.format("Address{bytes=%s}", HexConverter.encode(this.bytes));
  }
}
