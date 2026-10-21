package org.unicitylabs.sdk.api.bft.verification;

/**
 * Why a unicity certificate was not accepted, as a typed reason of
 * {@link UnicityCertificateVerificationResult#getFailure()}. The names of the rule results in the
 * result tree stay stable; this enum is the machine-readable summary of the first failing check.
 */
public enum UnicityCertificateFailure {
  /** The certificate is of a partition other than the aggregation partition. */
  PARTITION_MISMATCH(false),
  /** Input record, technical record hash or another field of the certificate body is invalid. */
  BODY_INVALID(false),
  /** The shard path does not have one sibling per shard bit, or an element is missing. */
  SHARD_PATH_INVALID(false),
  /** The shard and partition paths do not evaluate to the hash the seal commits to. */
  SEAL_HASH_MISMATCH(false),
  /** The certificate violates a caller pin: the shard scheme or a shard configuration hash. */
  PIN_MISMATCH(false),
  /** The seal is structurally malformed: no hash, round 0, early timestamp or empty signatures. */
  MALFORMED_SEAL(false),
  /** The seal is of another network than the trusted authority. */
  NETWORK_MISMATCH(true),
  /** The seal is of another epoch than the trusted authority. */
  EPOCH_MISMATCH(true),
  /** The seal round is before the start of the authority's epoch. */
  BEFORE_EPOCH_START(true),
  /** The seal does not carry a quorum of valid signatures of the authority. */
  QUORUM_NOT_REACHED(true),
  /** The authority is of a kind this verification does not support (stake-weighted quorum). */
  UNSUPPORTED_AUTHORITY(true);

  private final boolean authorityRejection;

  UnicityCertificateFailure(boolean authorityRejection) {
    this.authorityRejection = authorityRejection;
  }

  /**
   * Tells whether the trusted authority refused the seal (network, epoch, epoch start, quorum)
   * rather than the certificate itself being wrong (body, partition, paths, seal hash, pins, seal
   * structure).
   *
   * @return true if the authority did not accept the seal
   */
  public boolean isAuthorityRejection() {
    return authorityRejection;
  }
}
