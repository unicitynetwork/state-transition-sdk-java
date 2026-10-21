package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Strict authentication of a unicity seal under an authority the caller owns.
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
   * Authenticates a seal. Always strict: an implementation never relaxes this check, whatever
   * policy it was created or used with elsewhere. Legacy quorum-only checking of historical proofs
   * is a separate, differently named operation (see
   * {@link SealVerificationSession#verifyLegacyQuorum}) and its success never stands for this one.
   *
   * @param seal the seal, possibly from an untrusted source
   * @return OK, or FAIL with the failing rule; malformed peer data never throws a generic
   *     null/index exception
   */
  VerificationResult<VerificationStatus> authenticate(UnicitySeal seal);
}
