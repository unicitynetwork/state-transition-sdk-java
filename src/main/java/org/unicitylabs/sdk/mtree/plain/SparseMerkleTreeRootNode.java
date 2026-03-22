package org.unicitylabs.sdk.mtree.plain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.mtree.CommonPath;

/**
 * Sparse merkle tree state for given root.
 */
public class SparseMerkleTreeRootNode {

  private final FinalizedNodeBranch root;

  private SparseMerkleTreeRootNode(FinalizedNodeBranch root) {
    this.root = root;
  }

  static SparseMerkleTreeRootNode create(
      FinalizedBranch left,
      FinalizedBranch right,
      HashAlgorithm hashAlgorithm
  ) {
    return new SparseMerkleTreeRootNode(
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
   * Get merkle tree path for requested path.
   *
   * @param path path
   * @return merkle tree path
   */
  public SparseMerkleTreePath getPath(BigInteger path) {
    return new SparseMerkleTreePath(
        this.root.getHash(),
        SparseMerkleTreeRootNode.generatePath(path, this.root)
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SparseMerkleTreeRootNode)) {
      return false;
    }
    SparseMerkleTreeRootNode that = (SparseMerkleTreeRootNode) o;
    return Objects.equals(this.root, that.root);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.root);
  }

  private static List<SparseMerkleTreePathStep> generatePath(
      BigInteger remainingPath,
      FinalizedBranch parent
  ) {
    if (parent instanceof LeafBranch) {
      LeafBranch leaf = (LeafBranch) parent;
      return List.of(new SparseMerkleTreePathStep(leaf.getPath(), leaf.getValue()));
    }

    FinalizedNodeBranch node = (FinalizedNodeBranch) parent;
    CommonPath commonPath = CommonPath.create(remainingPath, parent.getPath());
    remainingPath = remainingPath.shiftRight(commonPath.getLength());

    if (commonPath.getPath().compareTo(parent.getPath()) != 0
        || remainingPath.compareTo(BigInteger.ONE) == 0) {
      return List.of(
          new SparseMerkleTreePathStep(
              BigInteger.ZERO,
              node.getLeft() == null
                  ? null
                  : node.getLeft().getHash().getData()
          ),
          new SparseMerkleTreePathStep(
              node.getPath(),
              node.getRight() == null
                  ? null
                  : node.getRight().getHash().getData()
          )
      );
    }

    boolean isRight = remainingPath.testBit(0);
    FinalizedBranch branch = isRight ? node.getRight() : node.getLeft();
    FinalizedBranch siblingBranch = isRight ? node.getLeft() : node.getRight();

    SparseMerkleTreePathStep step = new SparseMerkleTreePathStep(
        node.getPath(),
        siblingBranch == null ? null : siblingBranch.getHash().getData()
    );

    if (branch == null) {
      return List.of(
          new SparseMerkleTreePathStep(
              isRight ? BigInteger.ONE : BigInteger.ZERO,
              null
          ),
          step
      );
    }

    List<SparseMerkleTreePathStep> list = new ArrayList<>(
        SparseMerkleTreeRootNode.generatePath(remainingPath, branch)
    );

    list.add(step);

    return List.copyOf(list);
  }
}
