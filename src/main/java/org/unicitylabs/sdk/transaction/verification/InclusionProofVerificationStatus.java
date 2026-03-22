package org.unicitylabs.sdk.transaction.verification;

public enum InclusionProofVerificationStatus {
  INVALID_TRUSTBASE,
  LEAF_VALUE_MISMATCH,
  MISSING_CERTIFICATION_DATA,
  TRANSACTION_HASH_MISMATCH,
  NOT_AUTHENTICATED,
  PATH_NOT_INCLUDED,
  PATH_INVALID,
  OK
}
