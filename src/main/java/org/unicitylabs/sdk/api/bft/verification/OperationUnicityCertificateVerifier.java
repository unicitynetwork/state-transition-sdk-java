package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;

/**
 * Certificate verifier of one operation. Identical certificate bodies and seals of the token and of
 * every nested token are checked once while they are resident in the bounded memos; one body check
 * per distinct certificate, one quorum check per distinct seal.
 *
 * <p>Obtained from {@link UnicityCertificateVerifier#beginOperation}; the constructor is not public,
 * so a verifier of an operation cannot be forged or shared across operations by mistake. Thread
 * safe.
 */
public final class OperationUnicityCertificateVerifier extends UnicityCertificateVerifier {

  private OperationUnicityCertificateVerifier() {
  }

  /**
   * Verifies the certificate of an inclusion proof under the policy of the operation. Network and
   * epoch/policy checks run on every call, memo hits included.
   *
   * @param trustBase the authority
   * @param inclusionProof the proof
   * @return verification result
   */
  @Override
  public UnicityCertificateVerificationResult verify(RootTrustBase trustBase,
      InclusionProof inclusionProof) {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Verifies a raw certificate under the policy of the operation, with the operation's seal
   * session, body memo and pins. Used for strict evidence such as non-inclusion.
   *
   * @param trustBase the authority
   * @param certificate the certificate
   * @return verification result
   */
  @Override
  public UnicityCertificateVerificationResult verifyCertificate(RootTrustBase trustBase,
      UnicityCertificate certificate) {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Seal authenticator of an authority within this operation.
   *
   * @param trustBase the authority; one session is built per distinct authority
   * @return seal session of the operation
   */
  public SealVerificationSession sessionFor(RootTrustBase trustBase) {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Policy of the operation.
   *
   * @return policy
   */
  public VerificationPolicy getPolicy() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Work counters of the operation.
   *
   * @return snapshot of the counters
   */
  public OperationVerificationStats getStats() {
    throw new UnsupportedOperationException("Implemented by package A");
  }
}
