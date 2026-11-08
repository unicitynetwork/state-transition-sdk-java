package org.unicitylabs.sdk.api.bft.trustbase;

/** An entry is of an epoch before the genesis epoch of the history. */
public class TrustBaseBeforeGenesisException extends TrustBaseChainException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public TrustBaseBeforeGenesisException(String message) {
    super(message);
  }
}
