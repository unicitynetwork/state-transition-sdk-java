package org.unicitylabs.sdk.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Address;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Common test flows for token operations, matching TypeScript SDK's CommonTestFlow.
 */
public abstract class CommonTestFlow {

  protected StateTransitionClient client;
  protected RootTrustBase trustBase;
  protected PredicateVerifierService predicateVerifier;

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
        Address.fromPredicate(PayToPublicKeyPredicate.create(ALICE_SIGNING_SERVICE.getPublicKey()))
    );

    Token bobToken = TokenUtils.transferToken(
        this.client,
        this.trustBase,
        this.predicateVerifier,
        aliceToken.toCbor(),
        Address.fromPredicate(PayToPublicKeyPredicate.create(BOB_SIGNING_SERVICE.getPublicKey())),
        ALICE_SIGNING_SERVICE
    );

    Token carolToken = TokenUtils.transferToken(
        this.client,
        this.trustBase,
        this.predicateVerifier,
        bobToken.toCbor(),
        Address.fromPredicate(PayToPublicKeyPredicate.create(CAROL_SIGNING_SERVICE.getPublicKey())),
        BOB_SIGNING_SERVICE
    );

    Assertions.assertEquals(VerificationStatus.OK,
        carolToken.verify(this.trustBase, this.predicateVerifier).getStatus());
  }
}