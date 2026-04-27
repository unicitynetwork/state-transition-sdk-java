package org.unicitylabs.sdk.smt;

import java.util.Objects;

/**
 * Merkle tree path verification result.
 */
public class MerkleTreePathVerificationResult {

  private final boolean pathValid;
  private final boolean pathIncluded;

  /**
   * Create merkle tree path verification result.
   *
   * @param pathValid    is path valid
   * @param pathIncluded is path included for given state id
   */
  public MerkleTreePathVerificationResult(boolean pathValid, boolean pathIncluded) {
    this.pathValid = pathValid;
    this.pathIncluded = pathIncluded;
  }

  /**
   * Is path valid.
   *
   * @return true if path is valid
   */
  public boolean isPathValid() {
    return this.pathValid;
  }

  /**
   * Is path included for given state id.
   *
   * @return true if is included
   */
  public boolean isPathIncluded() {
    return this.pathIncluded;
  }

  /**
   * Is verification successful.
   *
   * @return true if successful
   */
  public boolean isSuccessful() {
    return this.pathValid && this.pathIncluded;
  }

  @Override
  public String toString() {
    return String.format("MerkleTreePathVerificationResult{pathValid=%b, pathIncluded=%b}",
            pathValid, pathIncluded);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof MerkleTreePathVerificationResult)) {
      return false;
    }
    MerkleTreePathVerificationResult that = (MerkleTreePathVerificationResult) o;
    return pathValid == that.pathValid && pathIncluded == that.pathIncluded;
  }

  @Override
  public int hashCode() {
    return Objects.hash(pathValid, pathIncluded);
  }
}
