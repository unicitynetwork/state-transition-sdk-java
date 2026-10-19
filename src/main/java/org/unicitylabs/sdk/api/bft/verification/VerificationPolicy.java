package org.unicitylabs.sdk.api.bft.verification;

/**
 * How strictly a seal is checked against the authority.
 */
public enum VerificationPolicy {
  /**
   * Network, epoch, epoch start round, seal structure and quorum. Required for consolidation
   * evidence and targets, partition views and non-inclusion.
   */
  STRICT,
  /**
   * Network, seal hash and quorum only. The reference behavior for historical token proofs, whose
   * seals may be of earlier epochs. Never exposed as strict authentication, and a success under it
   * never admits a strict operation.
   */
  LEGACY_QUORUM
}
