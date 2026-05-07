package org.unicitylabs.sdk.smt.radix;

import org.unicitylabs.sdk.crypto.hash.DataHash;

/**
 * Finalized branch in sparse merkle tree.
 */
public interface FinalizedBranch extends Branch {

  /**
   * Get hash of the branch.
   *
   * @return hash
   */
  DataHash getHash();
}
