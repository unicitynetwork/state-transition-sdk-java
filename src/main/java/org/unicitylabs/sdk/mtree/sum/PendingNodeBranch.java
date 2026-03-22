package org.unicitylabs.sdk.mtree.sum;

import java.math.BigInteger;
import java.util.Objects;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

/**
 * Pending node branch in a sparse merkle sum tree.
 */
class PendingNodeBranch implements NodeBranch {

  private final BigInteger path;
  private final Branch left;
  private final Branch right;

  /**
   * Create a pending node branch.
   *
   * @param path  path of the branch
   * @param left  left branch
   * @param right right branch
   */
  public PendingNodeBranch(BigInteger path, Branch left, Branch right) {
    this.path = path;
    this.left = left;
    this.right = right;
  }

  @Override
  public BigInteger getPath() {
    return this.path;
  }

  @Override
  public Branch getLeft() {
    return this.left;
  }

  @Override
  public Branch getRight() {
    return this.right;
  }

  @Override
  public FinalizedNodeBranch finalize(HashAlgorithm hashAlgorithm) {
    return FinalizedNodeBranch.create(this.path, this.left.finalize(hashAlgorithm),
        this.right.finalize(hashAlgorithm), hashAlgorithm);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof PendingNodeBranch)) {
      return false;
    }
    PendingNodeBranch that = (PendingNodeBranch) o;
    return Objects.equals(this.path, that.path) && Objects.equals(this.left, that.left)
        && Objects.equals(this.right, that.right);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.path, this.left, this.right);
  }
}
