package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * The quorum check a {@link SealVerificationSession} runs on every distinct miss of its memo: the
 * signatures of a seal against the trust-base entry of the seal's epoch. The default is
 * {@link org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealQuorumSignaturesVerificationRule}.
 * A custom rule is the extension point for an instrumented or hardware-backed check; the session
 * still places the seal in the history and bounds the work around it.
 */
@FunctionalInterface
public interface SealQuorumRule {

  /**
   * Check the seal's signatures against the entry.
   *
   * @param entry entry of the seal's epoch
   * @param seal the seal, within the signature-count bound
   * @return OK when the entry's weighted quorum signed, FAIL otherwise; never throws for malformed
   *     signature material
   */
  VerificationResult<VerificationStatus> verify(TrustBaseEntry entry, UnicitySeal seal);
}
