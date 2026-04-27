package org.unicitylabs.sdk.smt.sum;

import org.unicitylabs.sdk.smt.sum.SparseMerkleSumTree.LeafValue;

/**
 * Leaf branch in a sparse merkle sum tree.
 */
interface LeafBranch extends Branch {

  /**
   * Get value stored in the leaf.
   *
   * @return value stored in the leaf
   */
  LeafValue getValue();
}
