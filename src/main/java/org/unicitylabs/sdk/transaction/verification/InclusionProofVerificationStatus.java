package org.unicitylabs.sdk.transaction.verification;

/**
 * Status codes returned by inclusion proof verification.
 */
public enum InclusionProofVerificationStatus {
  /** The provided trust base is invalid or cannot be used for verification. */
  INVALID_TRUSTBASE,
  /** Certification data required for verification is missing. */
  MISSING_CERTIFICATION_DATA,
  /** Transaction hash does not match the value referenced by the proof. */
  TRANSACTION_HASH_MISMATCH,
  /** Proof authentication failed. */
  NOT_AUTHENTICATED,
  /** Proof path is not included in the committed tree state. */
  PATH_NOT_INCLUDED,

  INCLUSION_CERTIFICATE_MISSING,
  /** Proof path structure or hashes are invalid. */
  PATH_INVALID,
  /** Shard id of the unicity certificate does not match the transaction state id. */
  SHARD_ID_MISMATCH,
  /** Inclusion proof verification succeeded. */
  OK
}
