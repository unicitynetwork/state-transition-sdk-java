package org.unicitylabs.sdk.smt;

/**
 * Exception thrown when a branch already exists at a given path in the merkle tree.
 */
public class BranchExistsException extends Exception {

  /**
   * Create exception indicating that a branch already exists at the specified path.
   */
  public BranchExistsException() {
    super("Branch already exists at this path.");
  }
}
