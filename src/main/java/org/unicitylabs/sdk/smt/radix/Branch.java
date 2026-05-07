package org.unicitylabs.sdk.smt.radix;

import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

import java.math.BigInteger;

/**
 * Sparse merkle tree branch structure.
 */
public interface Branch {

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
