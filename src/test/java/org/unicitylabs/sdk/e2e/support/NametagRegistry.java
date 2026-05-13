package org.unicitylabs.sdk.e2e.support;

import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.unicityid.CertifiedUnicityIdMintTransaction;
import org.unicitylabs.sdk.unicityid.UnicityId;
import org.unicitylabs.sdk.unicityid.UnicityIdMintTransaction;
import org.unicitylabs.sdk.unicityid.UnicityIdToken;
import org.unicitylabs.sdk.util.InclusionProofUtils;

/**
 * Test-side helper that mints a {@link UnicityIdToken} representing a user's
 * nametag registration. Mirrors {@code registerNametag} from the TS BDD
 * support layer.
 */
public final class NametagRegistry {

  private NametagRegistry() {}

  /**
   * Registers a nametag for the given user in the given domain.
   *
   * @param client            state transition client
   * @param trustBase         root trust base
   * @param predicateVerifier predicate verifier service
   * @param userPredicate     the user's signing predicate (used as recipient + targetPredicate)
   * @param name              nametag without the leading {@code @}
   * @param domain            registration domain (defaults to "bdd/test")
   * @return the freshly minted UnicityIdToken
   */
  public static UnicityIdToken registerNametag(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      SignaturePredicate userPredicate,
      String name,
      String domain) throws Exception {

    SigningService nametagSigningService = SigningService.generate();
    SignaturePredicate nametagSigningPredicate =
        SignaturePredicate.fromSigningService(nametagSigningService);
    UnicityId unicityId = new UnicityId(name, domain == null ? "bdd/test" : domain);

    UnicityIdMintTransaction mintTransaction = UnicityIdMintTransaction.create(
        nametagSigningPredicate,
        userPredicate,
        unicityId,
        TokenType.generate(),
        userPredicate);

    CertificationData certificationData = CertificationData.fromTransaction(
        mintTransaction,
        SignaturePredicateUnlockScript.create(mintTransaction, nametagSigningService));

    CertificationResponse response = client.submitCertificationRequest(certificationData).get();
    if (response.getStatus() != CertificationStatus.SUCCESS) {
      throw new IllegalStateException(
          "Nametag registration failed: " + response.getStatus());
    }

    InclusionProof inclusionProof = InclusionProofUtils.waitInclusionProof(
        client, trustBase, predicateVerifier, mintTransaction).get();
    CertifiedUnicityIdMintTransaction certified =
        mintTransaction.toCertifiedTransaction(trustBase, predicateVerifier, inclusionProof);
    return UnicityIdToken.mint(trustBase, predicateVerifier, certified);
  }
}
