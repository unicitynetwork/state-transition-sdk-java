package org.unicitylabs.sdk.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.unicityid.UnicityId;
import org.unicitylabs.sdk.unicityid.UnicityIdMintTransaction;
import org.unicitylabs.sdk.unicityid.UnicityIdToken;
import org.unicitylabs.sdk.util.InclusionProofUtils;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Common test flows for token operations, matching TypeScript SDK's CommonTestFlow.
 */
public abstract class CommonTestFlow {

  protected StateTransitionClient client;
  protected RootTrustBase trustBase;
  protected PredicateVerifierService predicateVerifier;
  protected MintJustificationVerifierService mintJustificationVerifier;

  private static final SigningService ALICE_SIGNING_SERVICE = SigningService.generate();
  private static final SigningService BOB_SIGNING_SERVICE = SigningService.generate();
  private static final SigningService CAROL_SIGNING_SERVICE = SigningService.generate();

  /**
   * Test basic token transfer flow: Alice -> Bob -> Carol
   */
  @Test
  public void testTransferFlow() throws Exception {
    Token aliceToken = TokenUtils.mintToken(
            this.client,
            this.trustBase,
            this.predicateVerifier,
            this.mintJustificationVerifier,
            PayToPublicKeyPredicate.create(ALICE_SIGNING_SERVICE.getPublicKey())
    );

    Token bobToken = TokenUtils.transferToken(
            this.client,
            this.trustBase,
            this.predicateVerifier,
            this.mintJustificationVerifier,
            aliceToken.toCbor(),
            PayToPublicKeyPredicate.create(BOB_SIGNING_SERVICE.getPublicKey()),
            ALICE_SIGNING_SERVICE
    );

    Token carolToken = TokenUtils.transferToken(
            this.client,
            this.trustBase,
            this.predicateVerifier,
            this.mintJustificationVerifier,
            bobToken.toCbor(),
            PayToPublicKeyPredicate.create(CAROL_SIGNING_SERVICE.getPublicKey()),
            BOB_SIGNING_SERVICE
    );

    Assertions.assertEquals(VerificationStatus.OK,
            carolToken.verify(this.trustBase, this.predicateVerifier, this.mintJustificationVerifier).getStatus());
  }

  /**
   * Default successful flow: mint a unicity-id token and then mint a regular token whose recipient
   * is the unicity-id token's target predicate.
   */
  @Test
  public void testUnicityIdMintFlow() throws Exception {
    SigningService unicityIdSigningService = SigningService.generate();
    PayToPublicKeyPredicate targetPredicate = PayToPublicKeyPredicate.create(
            ALICE_SIGNING_SERVICE.getPublicKey());

    UnicityId unicityId = new UnicityId("testuser", "unicity-labs/test");
    UnicityIdMintTransaction unicityIdMintTransaction = UnicityIdMintTransaction.create(
            PayToPublicKeyPredicate.fromSigningService(unicityIdSigningService),
            targetPredicate,
            unicityId,
            TokenType.generate(),
            targetPredicate
    );

    CertificationData unicityIdCertificationData = CertificationData.fromTransaction(
            unicityIdMintTransaction,
            PayToPublicKeyPredicateUnlockScript.create(unicityIdMintTransaction, unicityIdSigningService)
    );

    CertificationResponse unicityIdResponse = this.client
            .submitCertificationRequest(unicityIdCertificationData).get();
    Assertions.assertEquals(CertificationStatus.SUCCESS, unicityIdResponse.getStatus());

    UnicityIdToken aliceUnicityIdToken = UnicityIdToken.mint(
            this.trustBase,
            this.predicateVerifier,
            unicityIdMintTransaction.toCertifiedTransaction(
                    this.trustBase,
                    this.predicateVerifier,
                    InclusionProofUtils.waitInclusionProof(this.client, this.trustBase,
                            this.predicateVerifier, unicityIdMintTransaction).get()
            )
    );

    Assertions.assertEquals(VerificationStatus.OK,
            aliceUnicityIdToken.verify(this.trustBase, this.predicateVerifier).getStatus());

    UnicityIdToken decodedUnicityIdToken = UnicityIdToken.fromCbor(aliceUnicityIdToken.toCbor());
    Assertions.assertArrayEquals(aliceUnicityIdToken.toCbor(), decodedUnicityIdToken.toCbor());
    Assertions.assertEquals(aliceUnicityIdToken.getId(), decodedUnicityIdToken.getId());
    Assertions.assertEquals(VerificationStatus.OK,
            decodedUnicityIdToken.verify(this.trustBase, this.predicateVerifier).getStatus());

    Token aliceToken = TokenUtils.mintToken(
            this.client,
            this.trustBase,
            this.predicateVerifier,
            this.mintJustificationVerifier,
            aliceUnicityIdToken.getGenesis().getTargetPredicate()
    );

    Assertions.assertEquals(VerificationStatus.OK,
            aliceToken.verify(this.trustBase, this.predicateVerifier, this.mintJustificationVerifier)
                    .getStatus());
  }
}