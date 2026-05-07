package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.transaction.CertifiedMintTransaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Verifier for a specific kind of certified mint transaction justification, identified by a CBOR
 * tag. Implementations are registered with {@link MintJustificationVerifierService} and dispatched
 * based on the tag of the bytes stored in the mint transaction's justification field.
 */
public interface MintJustificationVerifier {

  /**
   * Get the CBOR tag identifying the justification kind handled by this verifier.
   *
   * @return CBOR tag
   */
  long getTag();

  /**
   * Verify the justification of the given certified mint transaction.
   *
   * @param transaction certified mint transaction whose justification is being verified
   * @param mintJustificationVerifierService dispatcher used to recursively verify nested mint
   *     justifications (for example, the burn token's mint chain)
   *
   * @return verification result
   */
  VerificationResult<VerificationStatus> verify(
          CertifiedMintTransaction transaction,
          MintJustificationVerifierService mintJustificationVerifierService);
}
