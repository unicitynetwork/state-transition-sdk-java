package org.unicitylabs.sdk.api.bft.trustbase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.util.HexConverter;

/** The key and the revision token of the trust base cache. */
public final class TrustBaseCacheKey {

  /** The trust namespace of the built-in release table. A private or test authority uses another. */
  public static final String PUBLIC_NAMESPACE = "public";
  /** The fixed namespace of a local test network opened from its own bundle. */
  public static final String LOCAL_NAMESPACE = "local";
  /** The most bytes of a namespace. */
  public static final int MAX_NAMESPACE_BYTES = 64;

  private TrustBaseCacheKey() {
  }

  /**
   * The key a session stores under: layout version, network, authenticated genesis entry id and
   * trust namespace. Never the endpoint, the current tip or the SDK version, so a pin bump finds the
   * old cache. The application adds its own user scope.
   *
   * @param network the network
   * @param genesisEntryId entry id of the genesis
   * @param namespace {@link #PUBLIC_NAMESPACE} or an explicit custom one
   * @return the key
   */
  public static String of(NetworkId network, byte[] genesisEntryId, String namespace) {
    Objects.requireNonNull(network, "network cannot be null");
    Objects.requireNonNull(namespace, "namespace cannot be null");
    return "utb/v1/" + network.getId() + "/" + HexConverter.encode(genesisEntryId) + "/"
        + namespace;
  }

  /**
   * Whether a namespace may name a private or test authority.
   *
   * @param namespace the namespace
   * @return true for 1 to 64 bytes, no slash, and not the public namespace
   */
  public static boolean isValidPrivateNamespace(String namespace) {
    int length = namespace.getBytes(StandardCharsets.UTF_8).length;
    return length >= 1 && length <= MAX_NAMESPACE_BYTES && !namespace.contains("/")
        && !PUBLIC_NAMESPACE.equals(namespace);
  }

  /**
   * The revision token of stored bytes: the lowercase hex SHA-256.
   *
   * @param bytes the stored bytes
   * @return the revision
   */
  public static String revision(byte[] bytes) {
    return HexConverter.encode(sha256(bytes));
  }

  /**
   * The SHA-256 of bytes.
   *
   * @param bytes the bytes
   * @return the digest
   */
  public static byte[] sha256Of(byte[] bytes) {
    return sha256(bytes);
  }

  static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static boolean same(byte[] a, byte[] b) {
    return Arrays.equals(a, b);
  }
}
