package org.unicitylabs.sdk.smt.sum;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.BigIntegerConverter;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Finalized node branch in a sparse merkle sum tree.
 */
class FinalizedNodeBranch implements NodeBranch, FinalizedBranch {

  private final BigInteger path;
  private final FinalizedBranch left;
  private final FinalizedBranch right;
  private final BigInteger counter;
  private final DataHash hash;

  private FinalizedNodeBranch(
          BigInteger path,
          FinalizedBranch left,
          FinalizedBranch right,
          BigInteger counter,
          DataHash hash
  ) {
    this.path = path;
    this.left = left;
    this.right = right;
    this.counter = counter;
    this.hash = hash;
  }

  /**
   * Create a finalized node branch.
   *
   * @param path          path of the branch
   * @param left          left branch
   * @param right         right branch
   * @param hashAlgorithm hash algorithm to use
   * @return finalized node branch
   */
  public static FinalizedNodeBranch create(
          BigInteger path,
          FinalizedBranch left,
          FinalizedBranch right,
          HashAlgorithm hashAlgorithm
  ) {
    byte[] leftHash = left == null ? null : left.getHash().getData();
    byte[] rightHash = right == null ? null : right.getHash().getData();
    BigInteger leftCounter = left == null ? BigInteger.ZERO : left.getCounter();
    BigInteger rightCounter = right == null ? BigInteger.ZERO : right.getCounter();

    DataHash hash = new DataHasher(hashAlgorithm)
            .update(
                    CborSerializer.encodeArray(
                            CborSerializer.encodeByteString(BigIntegerConverter.encode(path)),
                            CborSerializer.encodeNullable(leftHash, CborSerializer::encodeByteString),
                            CborSerializer.encodeByteString(BigIntegerConverter.encode(leftCounter)),
                            CborSerializer.encodeNullable(rightHash, CborSerializer::encodeByteString),
                            CborSerializer.encodeByteString(BigIntegerConverter.encode(rightCounter))
                    )
            )
            .digest();

    BigInteger counter = leftCounter.add(rightCounter);

    return new FinalizedNodeBranch(path, left, right, counter, hash);
  }

  @Override
  public BigInteger getPath() {
    return this.path;
  }

  @Override
  public FinalizedBranch getLeft() {
    return this.left;
  }

  @Override
  public FinalizedBranch getRight() {
    return this.right;
  }

  @Override
  public BigInteger getCounter() {
    return this.counter;
  }

  @Override
  public DataHash getHash() {
    return this.hash;
  }

  @Override
  public FinalizedNodeBranch finalize(HashAlgorithm hashAlgorithm) {
    return this; // Already finalized
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof FinalizedNodeBranch)) {
      return false;
    }
    FinalizedNodeBranch that = (FinalizedNodeBranch) o;
    return Objects.equals(this.path, that.path) && Objects.equals(this.left, that.left)
            && Objects.equals(this.right, that.right);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.path, this.left, this.right);
  }
}
