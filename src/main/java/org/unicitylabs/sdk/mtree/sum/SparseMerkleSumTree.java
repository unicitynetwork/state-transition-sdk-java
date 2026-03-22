package org.unicitylabs.sdk.mtree.sum;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.mtree.BranchExistsException;
import org.unicitylabs.sdk.mtree.CommonPath;
import org.unicitylabs.sdk.mtree.LeafOutOfBoundsException;

/**
 * Sparse Merkle Sum Tree implementation.
 */
public class SparseMerkleSumTree {

  private Branch left = null;
  private Branch right = null;

  private final HashAlgorithm hashAlgorithm;

  /**
   * Create a sparse merkle sum tree.
   *
   * @param hashAlgorithm hash algorithm to use
   */
  public SparseMerkleSumTree(HashAlgorithm hashAlgorithm) {
    this.hashAlgorithm = hashAlgorithm;
  }

  /**
   * Add a leaf to the tree.
   *
   * @param path  path of the leaf (must be greater than 0)
   * @param value value stored in the leaf
   * @throws BranchExistsException    if a branch already exists at the given path
   * @throws LeafOutOfBoundsException if a leaf already exists at the given path
   * @throws IllegalArgumentException if the path is less than or equal to 0 or if the counter is negative
   * @throws NullPointerException     if the path or value is null
   */
  public synchronized void addLeaf(BigInteger path, LeafValue value)
      throws BranchExistsException, LeafOutOfBoundsException {
    Objects.requireNonNull(path, "Path cannot be null");
    Objects.requireNonNull(value, "Value cannot be null");

    if (path.compareTo(BigInteger.ONE) < 0) {
      throw new IllegalArgumentException("Path must be greater than 0");
    }

    if (value.getCounter().signum() < 0) {
      throw new IllegalArgumentException("Counter must be an unsigned BigInteger.");
    }

    boolean isRight = path.testBit(0);
    Branch branch = isRight ? this.right : this.left;
    Branch result = branch != null
        ? SparseMerkleSumTree.buildTree(branch, path, value)
        : new PendingLeafBranch(path, value);

    if (isRight) {
      this.right = result;
    } else {
      this.left = result;
    }
  }

  /**
   * Calculate the root of the tree and its state.
   *
   * @return root node of the tree
   */
  public synchronized SparseMerkleSumTreeRootNode calculateRoot() {
    FinalizedBranch left = this.left != null ? this.left.finalize(this.hashAlgorithm) : null;
    FinalizedBranch right = this.right != null ? this.right.finalize(this.hashAlgorithm) : null;
    this.left = left;
    this.right = right;

    return SparseMerkleSumTreeRootNode.create(left, right, this.hashAlgorithm);
  }

  private static Branch buildTree(Branch branch, BigInteger remainingPath, LeafValue value)
      throws BranchExistsException, LeafOutOfBoundsException {
    CommonPath commonPath = CommonPath.create(remainingPath, branch.getPath());
    boolean isRight = remainingPath.shiftRight(commonPath.getLength()).testBit(0);

    if (commonPath.getPath().equals(remainingPath)) {
      throw new BranchExistsException();
    }

    if (branch instanceof LeafBranch) {
      if (commonPath.getPath().equals(branch.getPath())) {
        throw new LeafOutOfBoundsException();
      }

      LeafBranch leafBranch = (LeafBranch) branch;

      LeafBranch oldBranch = new PendingLeafBranch(
          branch.getPath().shiftRight(commonPath.getLength()), leafBranch.getValue());
      LeafBranch newBranch = new PendingLeafBranch(remainingPath.shiftRight(commonPath.getLength()),
          value);
      return new PendingNodeBranch(commonPath.getPath(), isRight ? oldBranch : newBranch,
          isRight ? newBranch : oldBranch);
    }

    NodeBranch nodeBranch = (NodeBranch) branch;

    // if node branch is split in the middle
    if (commonPath.getPath().compareTo(branch.getPath()) < 0) {
      LeafBranch newBranch = new PendingLeafBranch(remainingPath.shiftRight(commonPath.getLength()),
          value);
      NodeBranch oldBranch = new PendingNodeBranch(
          branch.getPath().shiftRight(commonPath.getLength()), nodeBranch.getLeft(),
          nodeBranch.getRight());
      return new PendingNodeBranch(commonPath.getPath(), isRight ? oldBranch : newBranch,
          isRight ? newBranch : oldBranch);
    }

    if (isRight) {
      return new PendingNodeBranch(nodeBranch.getPath(), nodeBranch.getLeft(),
          SparseMerkleSumTree.buildTree(nodeBranch.getRight(),
              remainingPath.shiftRight(commonPath.getLength()), value));
    }

    return new PendingNodeBranch(nodeBranch.getPath(),
        SparseMerkleSumTree.buildTree(nodeBranch.getLeft(),
            remainingPath.shiftRight(commonPath.getLength()), value), nodeBranch.getRight());
  }

  /**
   * Value stored in a leaf of the sparse merkle sum tree.
   */
  public static class LeafValue {

    private final byte[] value;
    private final BigInteger counter;

    /**
     * Create a leaf value.
     *
     * @param value   byte array value
     * @param counter unsigned counter
     * @throws NullPointerException if the value or counter is null
     */
    public LeafValue(byte[] value, BigInteger counter) {
      Objects.requireNonNull(value, "Value cannot be null");
      Objects.requireNonNull(counter, "Counter cannot be null");

      this.value = Arrays.copyOf(value, value.length);
      this.counter = counter;
    }

    /**
     * Get a copy of leaf byte value.
     *
     * @return bytes
     */
    public byte[] getValue() {
      return Arrays.copyOf(this.value, this.value.length);
    }

    /**
     * Get the counter.
     *
     * @return counter
     */
    public BigInteger getCounter() {
      return this.counter;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof LeafValue)) {
        return false;
      }
      LeafValue that = (LeafValue) o;
      return Arrays.equals(this.value, that.value) && Objects.equals(this.counter, that.counter);
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(this.value), this.counter);
    }
  }
}

