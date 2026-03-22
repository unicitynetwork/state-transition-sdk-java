package org.unicitylabs.sdk.mtree.plain;

import java.math.BigInteger;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

/**
 * Sparse merkle tree branch structure.
 */
interface Branch {

  /**
   * Get branch path from leaf to root.
   *
   * @return path
   */
  BigInteger getPath();

  /**
   * Finalize current branch.
   *
   * @param hashAlgorithm hash algorithm
   * @return finalized branch
   */
  FinalizedBranch finalize(HashAlgorithm hashAlgorithm);
}
