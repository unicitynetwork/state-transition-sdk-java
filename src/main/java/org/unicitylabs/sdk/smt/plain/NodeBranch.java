package org.unicitylabs.sdk.smt.plain;

/**
 * Node branch in merkle tree.
 */
interface NodeBranch extends Branch {

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
