package org.unicitylabs.sdk.smt.plain;

import java.math.BigInteger;
import java.util.Arrays;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.smt.BranchExistsException;
import org.unicitylabs.sdk.smt.CommonPath;
import org.unicitylabs.sdk.smt.LeafOutOfBoundsException;

/**
 * Sparse Merkle tree implementation.
 */
public class SparseMerkleTree {

  private Branch left = null;
  private Branch right = null;

  private final HashAlgorithm hashAlgorithm;

  /**
   * Create sparse Merkle tree with given hash algorithm.
   *
   * @param hashAlgorithm hash algorithm
   */
  public SparseMerkleTree(HashAlgorithm hashAlgorithm) {
    this.hashAlgorithm = hashAlgorithm;
  }

  /**
   * Add leaf to the tree at given path.
   *
   * @param path path of the leaf
   * @param data data of the leaf
   * @throws BranchExistsException    if branch already exists at the path
   * @throws LeafOutOfBoundsException if leaf is out of bounds
   * @throws IllegalArgumentException if path is less than 1
   */
  public synchronized void addLeaf(BigInteger path, byte[] data)
      throws BranchExistsException, LeafOutOfBoundsException {
    if (path.compareTo(BigInteger.ONE) < 0) {
      throw new IllegalArgumentException("Path must be greater than 0");
    }

    boolean isRight = path.testBit(0);
    Branch branch = isRight ? this.right : this.left;
    Branch result = branch != null
        ? SparseMerkleTree.buildTree(branch, path, Arrays.copyOf(data, data.length))
        : new PendingLeafBranch(path, Arrays.copyOf(data, data.length));

    if (isRight) {
      this.right = result;
    } else {
      this.left = result;
    }
  }

  /**
   * Calculate root of the tree.
   *
   * @return root node and its state
   */
  public synchronized SparseMerkleTreeRootNode calculateRoot() {
    FinalizedBranch left = this.left != null ? this.left.finalize(this.hashAlgorithm) : null;
    FinalizedBranch right = this.right != null ? this.right.finalize(this.hashAlgorithm) : null;
    this.left = left;
    this.right = right;

    return SparseMerkleTreeRootNode.create(left, right, this.hashAlgorithm);
  }

  private static Branch buildTree(Branch branch, BigInteger remainingPath, byte[] value)
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
          SparseMerkleTree.buildTree(nodeBranch.getRight(),
              remainingPath.shiftRight(commonPath.getLength()), value));
    }

    return new PendingNodeBranch(nodeBranch.getPath(),
        SparseMerkleTree.buildTree(nodeBranch.getLeft(),
            remainingPath.shiftRight(commonPath.getLength()), value), nodeBranch.getRight());
  }
}

