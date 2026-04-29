package org.unicitylabs.sdk.unicityid;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.TokenId;

import java.util.List;
import java.util.Objects;

/**
 * Human-readable identifier for a unicity token. The pair (domain, name) is hashed deterministically
 * to derive the corresponding {@link TokenId}.
 */
public final class UnicityId {

  private final String name;
  private final String domain;

  /**
   * Create a unicity id with name only (no domain).
   *
   * @param name token name
   */
  public UnicityId(String name) {
    this(name, null);
  }

  /**
   * Create a unicity id.
   *
   * @param name token name
   * @param domain optional domain; may be null
   */
  public UnicityId(String name, String domain) {
    this.name = Objects.requireNonNull(name, "name cannot be null");
    this.domain = domain;
  }

  /**
   * Get the token name.
   *
   * @return name
   */
  public String getName() {
    return this.name;
  }

  /**
   * Get the optional domain.
   *
   * @return domain, or null if not set
   */
  public String getDomain() {
    return this.domain;
  }

  /**
   * Deserialize a unicity id from CBOR bytes.
   *
   * @param bytes CBOR bytes
   *
   * @return unicity id
   */
  public static UnicityId fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes, 2);
    return new UnicityId(
            CborDeserializer.decodeTextString(data.get(0)),
            CborDeserializer.decodeNullable(data.get(1), CborDeserializer::decodeTextString)
    );
  }

  /**
   * Serialize the unicity id to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
            CborSerializer.encodeTextString(this.name),
            CborSerializer.encodeNullable(this.domain, CborSerializer::encodeTextString)
    );
  }

  /**
   * Derive the token id from this unicity id by hashing the tagged ("NAMETAG_", domain, name)
   * tuple with SHA-256.
   *
   * @return derived token id
   */
  public TokenId toTokenId() {
    DataHash hash = new DataHasher(HashAlgorithm.SHA256)
            .update(
                    CborSerializer.encodeArray(
                            CborSerializer.encodeTextString("NAMETAG_"),
                            CborSerializer.encodeNullable(this.domain, CborSerializer::encodeTextString),
                            CborSerializer.encodeTextString(this.name)
                    )
            )
            .digest();
    return new TokenId(hash.getData());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof UnicityId)) {
      return false;
    }
    UnicityId that = (UnicityId) o;
    return this.name.equals(that.name) && Objects.equals(this.domain, that.domain);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.name, this.domain);
  }

  @Override
  public String toString() {
    return "@" + (this.domain != null ? this.domain + "/" : "") + this.name;
  }
}
