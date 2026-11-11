package org.unicitylabs.sdk.api.bft.trustbase;

/** Why a cache was not used. */
public enum CacheRejection {
  /** Not a complete canonical bundle that verifies strictly from its genesis. */
  MALFORMED,
  /** A bundle of another network. */
  WRONG_NETWORK,
  /** A bundle of another genesis. */
  WRONG_GENESIS,
  /** Diverges from, or does not reach, the release checkpoint. */
  PIN_MISMATCH,
  /** Larger than the read budget. */
  OVERSIZE
}
