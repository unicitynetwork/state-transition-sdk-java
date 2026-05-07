package org.unicitylabs.sdk.smt.radix;

import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.smt.BranchExistsException;
import org.unicitylabs.sdk.smt.CommonPath;
import org.unicitylabs.sdk.smt.LeafOutOfBoundsException;
import org.unicitylabs.sdk.util.BitString;

import java.math.BigInteger;

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
   * @param key path of the leaf
   * @param data data of the leaf
   * @throws BranchExistsException    if branch already exists at the path
   * @throws LeafOutOfBoundsException if leaf is out of bounds
   * @throws IllegalArgumentException if path is less than 1
   */
  public synchronized void addLeaf(byte[] key, byte[] data)
          throws BranchExistsException, LeafOutOfBoundsException {
    BigInteger path = BitString.fromBytesReversedLSB(key).toBigInteger();

    if (path.compareTo(BigInteger.ONE) <= 0) {
      throw new IllegalArgumentException("Path must be greater than 0");
    }

    boolean isRight = path.testBit(0);
    Branch branch = isRight ? this.right : this.left;
    Branch result = branch != null
            ? SparseMerkleTree.buildTree(branch, path, 0, key, data)
            : new PendingLeafBranch(path, key, data);

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
  public synchronized FinalizedNodeBranch calculateRoot() {
    FinalizedBranch left = this.left != null ? this.left.finalize(this.hashAlgorithm) : null;
    FinalizedBranch right = this.right != null ? this.right.finalize(this.hashAlgorithm) : null;
    this.left = left;
    this.right = right;

    return new PendingNodeBranch(BigInteger.ONE, 0, left, right).finalize(hashAlgorithm);
  }

  private static Branch buildTree(Branch branch, BigInteger remainingPath, int depth, byte[] key,
                                  byte[] value) throws BranchExistsException, LeafOutOfBoundsException {
    CommonPath commonPath = CommonPath.create(remainingPath, branch.getPath());
    int commonPathLength = commonPath.getLength();
    boolean isRight = remainingPath.shiftRight(commonPathLength).testBit(0);

    if (commonPath.getPath().equals(remainingPath)) {
      throw new BranchExistsException();
    }

    if (branch instanceof LeafBranch) {
      if (commonPath.getPath().equals(branch.getPath())) {
        throw new LeafOutOfBoundsException();
      }

      LeafBranch leafBranch = (LeafBranch) branch;

      LeafBranch oldBranch = new PendingLeafBranch(
              branch.getPath().shiftRight(commonPathLength), leafBranch.getKey(),
              leafBranch.getValue());
      LeafBranch newBranch = new PendingLeafBranch(
              remainingPath.shiftRight(commonPathLength), key, value);
      return new PendingNodeBranch(commonPath.getPath(), depth + commonPathLength,
              isRight ? oldBranch : newBranch, isRight ? newBranch : oldBranch);
    }

    NodeBranch nodeBranch = (NodeBranch) branch;

    // if node branch is split in the middle
    if (commonPath.getPath().compareTo(branch.getPath()) < 0) {
      LeafBranch newBranch = new PendingLeafBranch(
              remainingPath.shiftRight(commonPathLength), key, value);
      NodeBranch oldBranch = new PendingNodeBranch(
              branch.getPath().shiftRight(commonPathLength), nodeBranch.getDepth(),
              nodeBranch.getLeft(), nodeBranch.getRight());
      return new PendingNodeBranch(commonPath.getPath(), depth + commonPathLength,
              isRight ? oldBranch : newBranch, isRight ? newBranch : oldBranch);
    }

    if (isRight) {
      return new PendingNodeBranch(nodeBranch.getPath(), nodeBranch.getDepth(),
              nodeBranch.getLeft(),
              SparseMerkleTree.buildTree(nodeBranch.getRight(),
                      remainingPath.shiftRight(commonPathLength), depth + commonPathLength, key, value));
    }

    return new PendingNodeBranch(nodeBranch.getPath(), nodeBranch.getDepth(),
            SparseMerkleTree.buildTree(nodeBranch.getLeft(),
                    remainingPath.shiftRight(commonPathLength), depth + commonPathLength, key, value),
            nodeBranch.getRight());
  }
}

