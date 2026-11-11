package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Authentication of a unicity seal under a trust-base history the caller owns.
 *
 * <p>Strict means: the seal is structurally valid, its network, epoch and root chain round fit the
 * authority snapshot (the round is not before the start of the epoch) and the quorum of signatures
 * of that authority is reached. There is no caller-supplied "already verified" flag: an
 * implementation decides from the seal and its own snapshot only.
 */
public interface SealAuthenticator {

  /**
   * Identity of the authority snapshot and policy this authenticator applies. Equal identities mean
   * equal acceptance decisions, so results and derived evidence may be shared under one identity
   * and never across identities.
   *
   * @return identity text
   */
  String getIdentity();

  /**
   * Authenticates a seal. Always strict: an implementation never relaxes this check. The seal is
   * placed in the entry of its own epoch of the trust-base history, so a seal of an earlier epoch
   * is judged by that epoch's entry and by no other; there is no quorum-only mode.
   *
   * @param seal the seal, possibly from an untrusted source
   * @return OK, or FAIL with the failing rule; malformed peer data never throws a generic
   *     null/index exception
   */
  VerificationResult<VerificationStatus> authenticate(UnicitySeal seal);
}
