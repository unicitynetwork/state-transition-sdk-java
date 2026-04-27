package org.unicitylabs.sdk.smt.plain;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

/**
 * Pending leaf branch in a sparse merkle tree.
 */
class PendingLeafBranch implements LeafBranch {

  private final BigInteger path;
  private final byte[] value;

  /**
   * Create a pending leaf branch.
   *
   * @param path  path of the branch
   * @param value value stored in the leaf
   */
  public PendingLeafBranch(BigInteger path, byte[] value) {
    this.path = path;
    this.value = value;
  }

  @Override
  public BigInteger getPath() {
    return this.path;
  }

  @Override
  public byte[] getValue() {
    return Arrays.copyOf(this.value, this.value.length);
  }

  @Override
  public FinalizedLeafBranch finalize(HashAlgorithm hashAlgorithm) {
    return FinalizedLeafBranch.create(this.path, this.value, hashAlgorithm);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof PendingLeafBranch)) {
      return false;
    }
    PendingLeafBranch that = (PendingLeafBranch) o;
    return Objects.equals(this.path, that.path) && Objects.deepEquals(this.value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.path, Arrays.hashCode(this.value));
  }
}
