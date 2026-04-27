package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.BitString;
import org.unicitylabs.sdk.util.HexConverter;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

public class TokenType {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final byte[] bytes;

  public TokenType(byte[] bytes) {
    Objects.requireNonNull(bytes, "Token type cannot be null");

    this.bytes = Arrays.copyOf(bytes, bytes.length);
  }

  public byte[] getBytes() {
    return Arrays.copyOf(this.bytes, this.bytes.length);
  }

  public static TokenType generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return new TokenType(bytes);
  }

  public static TokenType fromCbor(byte[] bytes) {
    return new TokenType(CborDeserializer.decodeByteString(bytes));
  }

  public byte[] toCbor() {
    return CborSerializer.encodeByteString(this.bytes);
  }

  public BitString toBitString() {
    return BitString.fromBytes(this.bytes);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TokenType)) {
      return false;
    }
    TokenType tokenId = (TokenType) o;
    return Arrays.equals(this.bytes, tokenId.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(this.bytes);
  }

  @Override
  public String toString() {
    return String.format("TokenType[%s]", HexConverter.encode(this.bytes));
  }
}
