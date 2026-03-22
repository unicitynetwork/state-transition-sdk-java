package org.unicitylabs.sdk.mtree.sum;

import java.math.BigInteger;
import org.unicitylabs.sdk.crypto.hash.DataHash;

/**
 * Finalized branch in sparse merkle sum tree.
 */
interface FinalizedBranch extends Branch {

  /**
   * Get hash of the branch.
   *
   * @return hash
   */
  DataHash getHash();

  /**
   * Get counter of the branch.
   *
   * @return counter
   */
  BigInteger getCounter();
}
