package org.unicitylabs.sdk.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.smt.SparseMerkleTreePathUtils;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Non-inclusion certificate of the radix sparse Merkle tree: either the certified empty tree (empty
 * encoding) or the authenticated terminal leaf reached by key-directed descent together with the
 * siblings of every junction on the way.
 *
 * <p>Wire form of a non-empty certificate, exactly
 * {@code bitmap[32] || sibling[32] * popcount(bitmap) || terminalKey[32] || terminalValue[32]}.
 * Siblings run from the root to the leaf; bitmap bit {@code d} (most significant bit first) marks a
 * junction at depth {@code d}. The queried key is not part of the wire.
 *
 * <p>Immutable. Unverified until {@link NonInclusionVerifier} accepts it under an authenticated
 * unicity certificate.
 */
public final class NonInclusionCertificate {

  private static final int BITMAP_SIZE = 32;
  private static final int HASH_SIZE = 32;
  private static final int KEY_SIZE = 32;
  private static final int VALUE_SIZE = 32;
  private static final int MAX_DEPTH = 255;

  private static final NonInclusionCertificate EMPTY =
      new NonInclusionCertificate(null, null, null, null);

  private final byte[] bitmap;
  private final List<byte[]> siblings;
  private final byte[] terminalKey;
  private final byte[] terminalValue;

  private NonInclusionCertificate(byte[] bitmap, List<byte[]> siblings, byte[] terminalKey,
      byte[] terminalValue) {
    this.bitmap = bitmap;
    this.siblings = siblings;
    this.terminalKey = terminalKey;
    this.terminalValue = terminalValue;
  }

  /**
   * The certificate of the certified empty tree.
   *
   * @return empty tree certificate
   */
  public static NonInclusionCertificate emptyTree() {
    return EMPTY;
  }

  /**
   * Decodes a certificate in the exact profile.
   *
   * @param bytes the encoded certificate; empty for the empty tree
   * @return certificate
   * @throws IllegalArgumentException if the length or the sibling count does not match the bitmap
   */
  public static NonInclusionCertificate decode(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes cannot be null");
    if (bytes.length == 0) {
      return EMPTY;
    }
    if (bytes.length < BITMAP_SIZE) {
      throw new IllegalArgumentException("Non-inclusion certificate bitmap is truncated.");
    }
    byte[] bitmap = Arrays.copyOfRange(bytes, 0, BITMAP_SIZE);
    int count = popcount(bitmap);
    long expected = (long) BITMAP_SIZE + (long) count * HASH_SIZE + KEY_SIZE + VALUE_SIZE;
    if (bytes.length != expected) {
      throw new IllegalArgumentException(String.format(
          "Non-inclusion certificate length is %d, expected %d.", bytes.length, expected));
    }
    List<byte[]> siblings = new ArrayList<>(count);
    int offset = BITMAP_SIZE;
    for (int i = 0; i < count; i++) {
      siblings.add(Arrays.copyOfRange(bytes, offset, offset + HASH_SIZE));
      offset += HASH_SIZE;
    }
    byte[] key = Arrays.copyOfRange(bytes, offset, offset + KEY_SIZE);
    byte[] value = Arrays.copyOfRange(bytes, offset + KEY_SIZE, offset + KEY_SIZE + VALUE_SIZE);
    return new NonInclusionCertificate(bitmap, Collections.unmodifiableList(siblings), key, value);
  }

  /**
   * Encodes the certificate.
   *
   * @return the wire bytes; empty for the empty tree
   */
  public byte[] encode() {
    if (isEmptyTree()) {
      return new byte[0];
    }
    byte[] out = new byte[BITMAP_SIZE + siblings.size() * HASH_SIZE + KEY_SIZE + VALUE_SIZE];
    System.arraycopy(bitmap, 0, out, 0, BITMAP_SIZE);
    int offset = BITMAP_SIZE;
    for (byte[] sibling : siblings) {
      System.arraycopy(sibling, 0, out, offset, HASH_SIZE);
      offset += HASH_SIZE;
    }
    System.arraycopy(terminalKey, 0, out, offset, KEY_SIZE);
    System.arraycopy(terminalValue, 0, out, offset + KEY_SIZE, VALUE_SIZE);
    return out;
  }

  /**
   * Tells if this is the certificate of the empty tree.
   *
   * @return true for the empty tree
   */
  public boolean isEmptyTree() {
    return bitmap == null;
  }

  /**
   * The terminal leaf key.
   *
   * @return copy of the key, null for the empty tree
   */
  public byte[] getTerminalKey() {
    return terminalKey == null ? null : Arrays.copyOf(terminalKey, KEY_SIZE);
  }

  /**
   * The terminal leaf value.
   *
   * @return copy of the value, null for the empty tree
   */
  public byte[] getTerminalValue() {
    return terminalValue == null ? null : Arrays.copyOf(terminalValue, VALUE_SIZE);
  }

  /**
   * Tells if the certificate authenticates the path of {@code key} to {@code root}: for every
   * junction the query and the terminal take the same side, and the fold of the terminal leaf with
   * the siblings reaches the root. The empty certificate matches exactly the empty root
   * ({@code null}); a non-empty one never does.
   *
   * @param key the queried key, 32 bytes
   * @param root the certified state root, null for the certified empty tree
   * @return true if the path is authentic
   */
  boolean authenticates(byte[] key, byte[] root) {
    if (key.length != KEY_SIZE) {
      return false;
    }
    if (isEmptyTree()) {
      return root == null;
    }
    if (root == null) {
      return false;
    }
    byte[] hash = new DataHasher(HashAlgorithm.SHA256)
        .update(new byte[] {0x00}).update(terminalKey).update(terminalValue).digest().getData();
    int position = siblings.size();
    for (int depth = MAX_DEPTH; depth >= 0; depth--) {
      if (SparseMerkleTreePathUtils.getBitAtDepth(bitmap, depth) == 0) {
        continue;
      }
      position--;
      if (position < 0) {
        return false;
      }
      int side = SparseMerkleTreePathUtils.getBitAtDepth(terminalKey, depth);
      if (SparseMerkleTreePathUtils.getBitAtDepth(key, depth) != side) {
        return false;
      }
      byte[] sibling = siblings.get(position);
      DataHasher node = new DataHasher(HashAlgorithm.SHA256)
          .update(new byte[] {0x01, (byte) depth})
          .update(SparseMerkleTreePathUtils.regionFromKey(terminalKey, depth));
      hash = (side == 1 ? node.update(sibling).update(hash) : node.update(hash).update(sibling))
          .digest().getData();
    }
    return position == 0 && Arrays.equals(hash, root);
  }

  /**
   * Tells if the terminal leaf is the queried key, that is the key is present.
   *
   * @param key the queried key
   * @return true if present
   */
  boolean terminalIs(byte[] key) {
    return !isEmptyTree() && Arrays.equals(terminalKey, key);
  }

  private static int popcount(byte[] bytes) {
    int count = 0;
    for (byte b : bytes) {
      count += Integer.bitCount(b & 0xff);
    }
    return count;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof NonInclusionCertificate)) {
      return false;
    }
    return Arrays.equals(encode(), ((NonInclusionCertificate) o).encode());
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(encode());
  }

  @Override
  public String toString() {
    return isEmptyTree() ? "NonInclusionCertificate{emptyTree}"
        : "NonInclusionCertificate{terminalKey=" + HexConverter.encode(terminalKey)
            + ", junctions=" + siblings.size() + "}";
  }
}
