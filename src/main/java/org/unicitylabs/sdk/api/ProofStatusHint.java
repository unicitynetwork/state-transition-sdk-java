package org.unicitylabs.sdk.api;

/**
 * Unauthenticated status the aggregator may attach to an inclusion proof lookup.
 *
 * <p>The hint is control flow only: it decides when a wait stops. It never makes a proof valid or
 * invalid, {@code UNKNOWN} is not a proof of absence, {@code EXPIRED} is not a proof that a state is
 * unspent, and {@code INCLUDED} has no value without a proof that verifies.
 */
public enum ProofStatusHint {
  /** The request is accepted and not yet certified. */
  PENDING("pending"),
  /** The request was dropped before it was included. */
  EXPIRED("expired"),
  /** The aggregator does not know the request. */
  UNKNOWN("unknown"),
  /** The aggregator says the state is certified. */
  INCLUDED("included");

  private final String wire;

  ProofStatusHint(String wire) {
    this.wire = wire;
  }

  /**
   * Wire value of the hint.
   *
   * @return lower case name
   */
  public String getWireValue() {
    return wire;
  }

  /**
   * Parses a status value. A value this version does not know is treated like no status at all.
   *
   * @param value wire value, may be null
   * @return the hint, or null when the value is absent or not recognized
   */
  public static ProofStatusHint fromWire(String value) {
    if (value == null) {
      return null;
    }
    for (ProofStatusHint hint : values()) {
      if (hint.wire.equals(value)) {
        return hint;
      }
    }
    return null;
  }
}
