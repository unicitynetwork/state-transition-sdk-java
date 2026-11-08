package org.unicitylabs.sdk.api.bft.trustbase;

/** The bytes are not a well-formed deterministic encoding (wrong tag, version or arity, bad bounds, trailing bytes, a second byte form). */
public class TrustBaseEncodingException extends TrustBaseException {

  /**
   * Create the exception.
   *
   * @param message what failed
   */
  public TrustBaseEncodingException(String message) {
    super(message);
  }
}
