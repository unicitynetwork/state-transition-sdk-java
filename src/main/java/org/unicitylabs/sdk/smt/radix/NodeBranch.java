package org.unicitylabs.sdk.smt.radix;

/**
 * Node branch in merkle tree.
 */
public interface NodeBranch extends Branch {

  int getDepth();

  /**
   * Get left branch.
   *
   * @return left branch
   */
  Branch getLeft();

  /**
   * Get right branch.
   *
   * @return right branch
   */
  Branch getRight();
}
