package org.unicitylabs.sdk.smt.plain;

import org.unicitylabs.sdk.crypto.hash.DataHash;

/**
 * Finalized branch in sparse merkle tree.
 */
interface FinalizedBranch extends Branch {

  /**
   * Get hash of the branch.
   *
   * @return hash
   */
  DataHash getHash();
}
