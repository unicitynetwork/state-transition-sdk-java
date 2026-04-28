package org.unicitylabs.sdk.utils;

import org.junit.jupiter.api.Assertions;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.UnlockScript;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.*;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.util.InclusionProofUtils;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.security.SecureRandom;

/**
 * Test helpers for minting and transferring certified tokens.
 */
public class TokenUtils {

  public static Token mintToken(
          StateTransitionClient client,
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          MintJustificationVerifierService mintJustificationVerifier,
          Predicate recipient
  ) throws Exception {
    return TokenUtils.mintToken(
            client,
            trustBase,
            predicateVerifier,
            mintJustificationVerifier,
            TokenId.generate(),
            TokenType.generate(),
            recipient,
            null,
            null
    );
  }

  public static Token mintToken(
          StateTransitionClient client,
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          MintJustificationVerifierService mintJustificationVerifier,
          Predicate recipient,
          byte[] justification,
          byte[] data
  ) throws Exception {
    return TokenUtils.mintToken(
            client,
            trustBase,
            predicateVerifier,
            mintJustificationVerifier,
            TokenId.generate(),
            TokenType.generate(),
            recipient,
            justification,
            data
    );
  }

  public static Token mintToken(
          StateTransitionClient client,
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          MintJustificationVerifierService mintJustificationVerifier,
          TokenId tokenId,
          TokenType tokenType,
          Predicate recipient,
          byte[] justification,
          byte[] data
  ) throws Exception {
    MintTransaction transaction = MintTransaction.create(
            recipient,
            tokenId,
            tokenType,
            justification,
            data
    );

    CertificationData certificationData = CertificationData.fromMintTransaction(transaction);

    CertificationResponse response = client.submitCertificationRequest(certificationData).get();
    if (response.getStatus() != CertificationStatus.SUCCESS) {
      throw new RuntimeException(
              String.format("Certification Request failed with status '%s'", response.getStatus()));
    }

    return Token.mint(
            trustBase,
            predicateVerifier,
            mintJustificationVerifier,
            transaction.toCertifiedTransaction(
                    trustBase,
                    predicateVerifier,
                    InclusionProofUtils.waitInclusionProof(client, trustBase, predicateVerifier, transaction).get()
            )
    );
  }


  /**
   * Deserialize token, build transfer transaction and submit certified transfer.
   *
   * @param client state transition client
   * @param trustBase trust base
   * @param predicateVerifier predicate verifier
   * @param tokenBytes serialized token bytes
   * @param recipient recipient address
   * @param signingService sender signing service
   *
   * @return transferred token
   *
   * @throws Exception when request or verification fails
   */
  public static Token transferToken(
          StateTransitionClient client,
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          MintJustificationVerifierService mintJustificationVerifier,
          byte[] tokenBytes,
          Predicate recipient,
          SigningService signingService
  ) throws Exception {
    Token token = Token.fromCbor(tokenBytes);
    Assertions.assertEquals(VerificationStatus.OK, token.verify(trustBase, predicateVerifier, mintJustificationVerifier).getStatus());

    byte[] x = new byte[32];
    new SecureRandom().nextBytes(x);

    TransferTransaction transaction = TransferTransaction.create(
            token,
            recipient,
            x,
            CborSerializer.encodeArray()
    );

    return TokenUtils.transferToken(
            client,
            trustBase,
            predicateVerifier,
            token,
            transaction,
            PayToPublicKeyPredicateUnlockScript.create(transaction, signingService)
    );
  }

  /**
   * Submit a prepared transfer transaction and return resulting transferred token.
   *
   * @param client state transition client
   * @param trustBase trust base
   * @param predicateVerifier predicate verifier
   * @param token source token
   * @param transaction transfer transaction
   * @param unlockScript unlock script for transaction
   *
   * @return transferred token
   *
   * @throws Exception when request or verification fails
   */
  public static Token transferToken(
          StateTransitionClient client,
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          Token token,
          TransferTransaction transaction,
          UnlockScript unlockScript
  ) throws Exception {
    CertificationResponse response = client.submitCertificationRequest(
            CertificationData.fromTransaction(transaction, unlockScript)
    ).get();

    if (response.getStatus() != CertificationStatus.SUCCESS) {
      throw new RuntimeException(
              String.format("Certification Request failed with status '%s'", response.getStatus()));
    }

    return token.transfer(
            trustBase,
            predicateVerifier,
            transaction.toCertifiedTransaction(
                    trustBase,
                    predicateVerifier,
                    InclusionProofUtils.waitInclusionProof(
                            client,
                            trustBase,
                            predicateVerifier,
                            transaction
                    ).get()
            )
    );
  }

}
