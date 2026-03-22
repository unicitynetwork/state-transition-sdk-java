package org.unicitylabs.sdk.mtree.sum;

import java.math.BigInteger;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

/**
 * Branch in a sparse merkle sum tree.
 */
interface Branch {

  /**
   * Get path of the branch.
   *
   * @return path
   */
  BigInteger getPath();

  /**
   * Finalize the branch by computing its hash.
   *
   * @param hashAlgorithm hash algorithm to use
   * @return finalized branch
   */
  FinalizedBranch finalize(HashAlgorithm hashAlgorithm);
}
