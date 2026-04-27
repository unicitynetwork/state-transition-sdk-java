package org.unicitylabs.sdk.transaction.verification;

public enum InclusionProofVerificationStatus {
  INVALID_TRUSTBASE,
  MISSING_CERTIFICATION_DATA,
  TRANSACTION_HASH_MISMATCH,
  NOT_AUTHENTICATED,
  INCLUSION_CERTIFICATE_MISSING,
  PATH_INVALID,
  OK
}
