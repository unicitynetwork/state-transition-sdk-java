package org.unicitylabs.sdk.smt.sum;

import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.smt.sum.SparseMerkleSumTree.LeafValue;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Pending leaf branch in a sparse merkle sum tree.
 */
class PendingLeafBranch implements LeafBranch {

  private final BigInteger path;
  private final LeafValue value;

  /**
   * Create a pending leaf branch.
   *
   * @param path  path of the branch
   * @param value value stored in the leaf
   */
  public PendingLeafBranch(BigInteger path, LeafValue value) {
    this.path = path;
    this.value = value;
  }

  @Override
  public BigInteger getPath() {
    return this.path;
  }

  @Override
  public LeafValue getValue() {
    return this.value;
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
    return Objects.hash(this.path, this.value);
  }
}
