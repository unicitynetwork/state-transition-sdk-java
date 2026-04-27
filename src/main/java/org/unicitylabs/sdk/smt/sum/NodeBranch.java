package org.unicitylabs.sdk.smt.sum;

/**
 * Node branch in sparse merkle sum tree.
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
