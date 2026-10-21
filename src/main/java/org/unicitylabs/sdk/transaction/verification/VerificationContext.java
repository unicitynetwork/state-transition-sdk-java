package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.verification.OperationUnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;

import java.util.Objects;

/**
 * Immutable bundle of the dependencies shared across a (possibly nested) token verification: the
 * single root of trust, the predicate verifier, the unicity certificate verifier, and the
 * mint-justification and token-issuance registries. A nested (e.g. burned source) token is always
 * verified under the same root of trust and registries as the outer token, and with the same
 * certificate verifier: when that is an operation verifier, identical certificate bodies and seals
 * of the outer and the nested tokens are checked once.
 *
 * <p>All verifiers must be supplied explicitly: a defaulted token-issuance verifier would be an
 * empty fail-closed registry that rejects every token, so there is no sensible default.
 */
public final class VerificationContext {

  private final RootTrustBase trustBase;
  private final PredicateVerifierService predicateVerifier;
  private final MintJustificationVerifierService mintJustificationVerifier;
  private final TokenIssuanceVerifierService tokenIssuanceVerifier;
  private final UnicityCertificateVerifier certificateVerifier;

  /**
   * Create a verification context with all dependencies and the plain certificate verifier. Token
   * verification begins its own operation on it, see {@link #forOperation()}.
   *
   * @param trustBase root trust base for the network
   * @param predicateVerifier predicate verifier
   * @param mintJustificationVerifier mint justification registry
   * @param tokenIssuanceVerifier token issuance registry
   */
  public VerificationContext(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          MintJustificationVerifierService mintJustificationVerifier,
          TokenIssuanceVerifierService tokenIssuanceVerifier
  ) {
    this(trustBase, predicateVerifier, mintJustificationVerifier, tokenIssuanceVerifier,
            new UnicityCertificateVerifier());
  }

  /**
   * Create a verification context with all dependencies and an explicit certificate verifier.
   *
   * @param trustBase root trust base for the network
   * @param predicateVerifier predicate verifier
   * @param mintJustificationVerifier mint justification registry
   * @param tokenIssuanceVerifier token issuance registry
   * @param certificateVerifier verifier of unicity certificates; a customized verifier keeps full
   *     control of the checks and is never wrapped
   */
  public VerificationContext(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          MintJustificationVerifierService mintJustificationVerifier,
          TokenIssuanceVerifierService tokenIssuanceVerifier,
          UnicityCertificateVerifier certificateVerifier
  ) {
    this.trustBase = Objects.requireNonNull(trustBase, "trustBase cannot be null");
    this.predicateVerifier = Objects.requireNonNull(predicateVerifier,
            "predicateVerifier cannot be null");
    this.mintJustificationVerifier = Objects.requireNonNull(mintJustificationVerifier,
            "mintJustificationVerifier cannot be null");
    this.tokenIssuanceVerifier = Objects.requireNonNull(tokenIssuanceVerifier,
            "tokenIssuanceVerifier cannot be null");
    this.certificateVerifier = Objects.requireNonNull(certificateVerifier,
            "certificateVerifier cannot be null");
  }

  /**
   * Get the unicity certificate verifier.
   *
   * @return certificate verifier
   */
  public UnicityCertificateVerifier getCertificateVerifier() {
    return this.certificateVerifier;
  }

  /**
   * The same dependencies with another certificate verifier.
   *
   * @param certificateVerifier verifier of unicity certificates
   * @return new context
   */
  public VerificationContext withCertificateVerifier(UnicityCertificateVerifier certificateVerifier) {
    return new VerificationContext(this.trustBase, this.predicateVerifier,
            this.mintJustificationVerifier, this.tokenIssuanceVerifier, certificateVerifier);
  }

  /**
   * Context for one verification operation: the same dependencies with the certificate verifier of
   * an operation under the legacy quorum policy of ordinary token proofs, which shares work
   * between the proofs of the token and of every nested token. A verifier that already is an
   * operation verifier is kept, so a caller that shares one operation across several tokens, or
   * chose the policy, stays in control; a customized verifier is kept as it is.
   *
   * @return context to verify one token with
   */
  public VerificationContext forOperation() {
    if (this.certificateVerifier instanceof OperationUnicityCertificateVerifier) {
      return this;
    }
    UnicityCertificateVerifier operation = this.certificateVerifier.beginOperation(
            VerificationPolicy.LEGACY_QUORUM, PartitionViewPolicy.none());
    if (operation == this.certificateVerifier) {
      return this;
    }
    return new VerificationContext(this.trustBase, this.predicateVerifier,
            this.mintJustificationVerifier, this.tokenIssuanceVerifier, operation);
  }

  /**
   * Get the root trust base.
   *
   * @return trust base
   */
  public RootTrustBase getTrustBase() {
    return this.trustBase;
  }

  /**
   * Get the predicate verifier.
   *
   * @return predicate verifier
   */
  public PredicateVerifierService getPredicateVerifier() {
    return this.predicateVerifier;
  }

  /**
   * Get the mint justification registry.
   *
   * @return mint justification verifier
   */
  public MintJustificationVerifierService getMintJustificationVerifier() {
    return this.mintJustificationVerifier;
  }

  /**
   * Get the token issuance registry.
   *
   * @return token issuance verifier
   */
  public TokenIssuanceVerifierService getTokenIssuanceVerifier() {
    return this.tokenIssuanceVerifier;
  }
}
