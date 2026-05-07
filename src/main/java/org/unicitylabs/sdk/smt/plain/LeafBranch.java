package org.unicitylabs.sdk.smt.plain;

/**
 * Leaf branch in a sparse merkle tree.
 */
interface LeafBranch extends Branch {

  /**
   * Get value stored in the leaf.
   *
   * @return value stored in the leaf
   */
  byte[] getValue();
}
