package org.unicitylabs.sdk.smt.sum;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.smt.CommonPath;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sparse Merkle Sum Tree root node.
 */
public class SparseMerkleSumTreeRootNode {

  private final FinalizedNodeBranch root;

  private SparseMerkleSumTreeRootNode(FinalizedNodeBranch root) {
    this.root = root;
  }

  static SparseMerkleSumTreeRootNode create(
          FinalizedBranch left,
          FinalizedBranch right,
          HashAlgorithm hashAlgorithm
  ) {
    return new SparseMerkleSumTreeRootNode(
            FinalizedNodeBranch.create(BigInteger.ONE, left, right, hashAlgorithm)
    );
  }

  /**
   * Get root hash.
   *
   * @return root hash
   */
  public DataHash getRootHash() {
    return this.root.getHash();
  }

  /**
   * Get root value.
   *
   * @return root value
   */
  public BigInteger getValue() {
    return this.root.getCounter();
  }

  /**
   * Get merkle sum tree path for requested path.
   *
   * @param path path
   * @return merkle tree path
   */
  public SparseMerkleSumTreePath getPath(BigInteger path) {
    return new SparseMerkleSumTreePath(
            this.root.getHash(),
            SparseMerkleSumTreeRootNode.generatePath(path, this.root)
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SparseMerkleSumTreeRootNode)) {
      return false;
    }
    SparseMerkleSumTreeRootNode that = (SparseMerkleSumTreeRootNode) o;
    return Objects.equals(this.root, that.root);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.root);
  }

  private static List<SparseMerkleSumTreePathStep> generatePath(
          BigInteger remainingPath,
          FinalizedBranch parent
  ) {
    if (parent instanceof LeafBranch) {
      LeafBranch leaf = (LeafBranch) parent;
      return List.of(new SparseMerkleSumTreePathStep(
              leaf.getPath(),
              leaf.getValue().getValue(),
              leaf.getValue().getCounter()
      ));
    }

    FinalizedNodeBranch node = (FinalizedNodeBranch) parent;
    CommonPath commonPath = CommonPath.create(remainingPath, parent.getPath());
    remainingPath = remainingPath.shiftRight(commonPath.getLength());

    if (commonPath.getPath().compareTo(parent.getPath()) != 0
            || remainingPath.compareTo(BigInteger.ONE) == 0) {
      return List.of(
              new SparseMerkleSumTreePathStep(
                      BigInteger.ZERO,
                      node.getLeft() == null
                              ? null
                              : node.getLeft().getHash().getData(),
                      node.getLeft() == null
                              ? BigInteger.ZERO
                              : node.getLeft().getCounter()
              ),
              new SparseMerkleSumTreePathStep(
                      node.getPath(),
                      node.getRight() == null
                              ? null
                              : node.getRight().getHash().getData(),
                      node.getRight() == null
                              ? BigInteger.ZERO
                              : node.getRight().getCounter()
              )
      );
    }

    boolean isRight = remainingPath.testBit(0);
    FinalizedBranch branch = isRight ? node.getRight() : node.getLeft();
    FinalizedBranch siblingBranch = isRight ? node.getLeft() : node.getRight();

    SparseMerkleSumTreePathStep step = new SparseMerkleSumTreePathStep(
            node.getPath(),
            siblingBranch == null ? null : siblingBranch.getHash().getData(),
            siblingBranch == null ? BigInteger.ZERO : siblingBranch.getCounter()
    );

    if (branch == null) {
      return List.of(
              new SparseMerkleSumTreePathStep(
                      isRight ? BigInteger.ONE : BigInteger.ZERO,
                      null,
                      BigInteger.ZERO
              ),
              step
      );
    }

    List<SparseMerkleSumTreePathStep> list = new ArrayList<>(
            SparseMerkleSumTreeRootNode.generatePath(remainingPath, branch)
    );

    list.add(step);

    return List.copyOf(list);
  }
}
