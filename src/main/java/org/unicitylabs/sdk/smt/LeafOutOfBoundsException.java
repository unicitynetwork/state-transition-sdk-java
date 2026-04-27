package org.unicitylabs.sdk.smt;

/**
 * Exception when leaf is out of bounds.
 */
public class LeafOutOfBoundsException extends Exception {

  /**
   * Create exception.
   */
  public LeafOutOfBoundsException() {
    super("Cannot extend tree through leaf.");
  }
}
