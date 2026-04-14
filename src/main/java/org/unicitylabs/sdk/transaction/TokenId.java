package org.unicitylabs.sdk.transaction;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.BitString;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Globally unique identifier of a token.
 */
public final class TokenId {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final byte[] bytes;

  /**
   * Create a token id from byte array.
   *
   * @param bytes token id bytes
   */
  public TokenId(byte[] bytes) {
    Objects.requireNonNull(bytes, "Token id cannot be null");

    this.bytes = Arrays.copyOf(bytes, bytes.length);
  }

  /**
   * Generate a random token id.
   *
   * @return token id
   */
  public static TokenId generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return new TokenId(bytes);
  }

  /**
   * Get token id bytes.
   *
   * @return token id bytes
   */
  public byte[] getBytes() {
    return Arrays.copyOf(this.bytes, this.bytes.length);
  }

  /**
   * Deserialize an token id from CBOR bytes.
   *
   * @param bytes CBOR encoded token id bytes
   *
   * @return token id
   */
  public static TokenId fromCbor(byte[] bytes) {
    return new TokenId(CborDeserializer.decodeByteString(bytes));
  }

  /**
   * Serialize token id to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeByteString(this.bytes);
  }

  /**
   * Convert token id to bit string.
   *
   * @return bit string
   */
  public BitString toBitString() {
    return new BitString(this.bytes);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TokenId)) {
      return false;
    }
    TokenId tokenId = (TokenId) o;
    return Arrays.equals(this.bytes, tokenId.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(this.bytes);
  }

  @Override
  public String toString() {
    return String.format("TokenId[%s]", HexConverter.encode(this.bytes));
  }
}
