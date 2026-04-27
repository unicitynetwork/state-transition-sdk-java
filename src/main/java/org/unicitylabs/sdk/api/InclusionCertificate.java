package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.smt.radix.FinalizedBranch;
import org.unicitylabs.sdk.smt.radix.FinalizedLeafBranch;
import org.unicitylabs.sdk.smt.radix.FinalizedNodeBranch;
import org.unicitylabs.sdk.util.BitString;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.LongConverter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class InclusionCertificate {
  private static final int BITMAP_SIZE = 32;
  private static final int MAX_DEPTH = 255;

  private final byte[] bitmap;
  private final List<DataHash> siblings;


  private InclusionCertificate(byte[] bitmap, List<DataHash> siblings) {
    this.bitmap = bitmap;
    this.siblings = siblings;
  }

  public static InclusionCertificate create(FinalizedNodeBranch root, byte[] key) {
    FinalizedBranch node = root;

    ArrayList<DataHash> siblings = new ArrayList<>();
    byte[] bitmap = new byte[InclusionCertificate.BITMAP_SIZE];
    BigInteger keyPath = BitString.fromBytesReversedLSB(key).toBigInteger();

    while (node != null) {
      if (node instanceof FinalizedLeafBranch) {
        FinalizedLeafBranch leaf = (FinalizedLeafBranch) node;
        if (!Arrays.equals(leaf.getKey(), key)) {
          throw new RuntimeException(String.format("Leaf not found for key: %s", HexConverter.encode(key)));
        }

        return new InclusionCertificate(bitmap, siblings);
      }

      FinalizedNodeBranch nodeBranch = (FinalizedNodeBranch) node;
      boolean isRight = keyPath.testBit(nodeBranch.getDepth());
      FinalizedBranch sibling = isRight ? nodeBranch.getLeft() : nodeBranch.getRight();
      if (sibling != null) {
        bitmap[nodeBranch.getDepth() / 8] |= (byte) (1 << nodeBranch.getDepth() % 8);
        siblings.add(sibling.getHash());
      }

      node = isRight ? nodeBranch.getRight() : nodeBranch.getLeft();
    }

    throw new RuntimeException("Could not construct inclusion certificate: Invalid path");
  }

  public static InclusionCertificate decode(byte[] bytes) {
    if (bytes.length < InclusionCertificate.BITMAP_SIZE) {
      throw new IllegalArgumentException("Inclusion Certificate bitmap is invalid.");
    }

    int siblingBytesLength = bytes.length - InclusionCertificate.BITMAP_SIZE;
    if (siblingBytesLength % HashAlgorithm.SHA256.getLength() != 0) {
      throw new IllegalArgumentException("Inclusion Certificate siblings are misaligned.");
    }

    int siblingsCount = 0;
    for (int i = 0; i < InclusionCertificate.BITMAP_SIZE; i++) {
      int x = bytes[i];
      x = x - ((x >>> 1) & 0x55);
      x = (x & 0x33) + ((x >>> 2) & 0x33);
      x = (x + (x >>> 4)) & 0x0f;
      siblingsCount += x;
    }

    if (siblingBytesLength / HashAlgorithm.SHA256.getLength() != siblingsCount) {
      throw new IllegalArgumentException("Inclusion Certificate siblings count does not match bitmap.");
    }

    ArrayList<DataHash> siblings = new ArrayList<>();
    for (int i = InclusionCertificate.BITMAP_SIZE; i < bytes.length; i += HashAlgorithm.SHA256.getLength()) {
      siblings.add(new DataHash(HashAlgorithm.SHA256, Arrays.copyOfRange(bytes, i, i + HashAlgorithm.SHA256.getLength())));
    }

    return new InclusionCertificate(Arrays.copyOfRange(bytes, 0, InclusionCertificate.BITMAP_SIZE), siblings);
  }

  public byte[] encode() {
    byte[] bytes = new byte[InclusionCertificate.BITMAP_SIZE + this.siblings.size() * HashAlgorithm.SHA256.getLength()];
    System.arraycopy(this.bitmap, 0, bytes, 0, InclusionCertificate.BITMAP_SIZE);
    int offset = InclusionCertificate.BITMAP_SIZE;
    for (DataHash sibling : this.siblings) {
      byte[] data = sibling.getData();
      System.arraycopy(data, 0, bytes, offset, data.length);
      offset += data.length;
    }
    return bytes;
  }

  public boolean verify(StateId leafKey, DataHash leafValue, DataHash expectedRootHash) {
    byte[] key = leafKey.getData();
    byte[] value = leafValue.getData();

    DataHash hash = new DataHasher(HashAlgorithm.SHA256)
            .update(new byte[]{0x00})
            .update(key)
            .update(value)
            .digest();

    BigInteger keyPath = BitString.fromBytesReversedLSB(key).toBigInteger();
    BigInteger bitmapPath = BitString.fromBytesReversedLSB(this.bitmap).toBigInteger();

    int position = this.siblings.size();
    for (int depth = InclusionCertificate.MAX_DEPTH; depth >= 0; depth--) {
      if (!bitmapPath.testBit(depth)) continue;

      position -= 1;
      if (position < 0) return false;

      DataHash sibling = this.siblings.get(position);

      byte[] left, right;
      if (keyPath.testBit(depth)) {
        left = sibling.getData();
        right = hash.getData();
      } else {
        left = hash.getData();
        right = sibling.getData();
      }

      hash = new DataHasher(HashAlgorithm.SHA256)
              .update(new byte[]{0x01})
              .update(LongConverter.encode(depth))
              .update(left)
              .update(right)
              .digest();
    }

    return position == 0 && hash.equals(expectedRootHash);
  }


  @Override
  public boolean equals(Object o) {
    if (!(o instanceof InclusionCertificate)) return false;
    InclusionCertificate that = (InclusionCertificate) o;
    return Objects.deepEquals(this.bitmap, that.bitmap) && Objects.equals(this.siblings, that.siblings);
  }

  @Override
  public int hashCode() {
    return Objects.hash(Arrays.hashCode(this.bitmap), this.siblings);
  }

  @Override
  public String toString() {
    return String.format("InclusionCertificate{bitmap=%s, siblings=%s}", HexConverter.encode(this.bitmap), this.siblings);
  }
}
