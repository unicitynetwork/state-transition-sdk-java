package org.unicitylabs.sdk.transaction.verification;

/**
 * Status codes returned by inclusion proof verification.
 */
public enum InclusionProofVerificationStatus {
  /** The provided trust base is invalid or cannot be used for verification. */
  INVALID_TRUSTBASE,
  /** Leaf value in the proof does not match the expected transaction. */
  LEAF_VALUE_MISMATCH,
  /** Certification data required for verification is missing. */
  MISSING_CERTIFICATION_DATA,
  /** Transaction hash does not match the value referenced by the proof. */
  TRANSACTION_HASH_MISMATCH,
  /** Proof authentication failed. */
  NOT_AUTHENTICATED,
  /** Proof path is not included in the committed tree state. */
  PATH_NOT_INCLUDED,
  /** Proof path structure or hashes are invalid. */
  PATH_INVALID,
  /** Inclusion proof verification succeeded. */
  OK
}
