package org.unicitylabs.sdk.smt.radix;

/**
 * Leaf branch in a sparse merkle tree.
 */
public interface LeafBranch extends Branch {

  byte[] getKey();

  /**
   * Get value stored in the leaf.
   *
   * @return value stored in the leaf
   */
  byte[] getValue();
}
