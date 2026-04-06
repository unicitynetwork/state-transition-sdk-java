package org.unicitylabs.sdk.transaction;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.BitString;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Type identifier of a token.
 */
public final class TokenType {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final byte[] bytes;

  /**
   * Create a token type from byte array.
   *
   * @param bytes token type bytes
   */
  public TokenType(byte[] bytes) {
    Objects.requireNonNull(bytes, "Token type cannot be null");

    this.bytes = Arrays.copyOf(bytes, bytes.length);
  }

  /**
   * Get token type bytes.
   *
   * @return token type bytes
   */
  public byte[] getBytes() {
    return Arrays.copyOf(this.bytes, this.bytes.length);
  }

  /**
   * Generate a random token type.
   *
   * @return token type
   */
  public static TokenType generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return new TokenType(bytes);
  }

  /**
   * Deserialize a token type from CBOR bytes.
   *
   * @param bytes CBOR encoded token type bytes
   *
   * @return token type
   */
  public static TokenType fromCbor(byte[] bytes) {
    return new TokenType(CborDeserializer.decodeByteString(bytes));
  }

  /**
   * Serialize token type to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeByteString(this.bytes);
  }

  /**
   * Convert token type to bit string.
   *
   * @return bit string
   */
  public BitString toBitString() {
    return new BitString(this.bytes);
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
