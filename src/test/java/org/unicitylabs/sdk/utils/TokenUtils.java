package org.unicitylabs.sdk.utils;

import java.security.SecureRandom;
import org.junit.jupiter.api.Assertions;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Address;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.util.InclusionProofUtils;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class TokenUtils {

  public static Token mintToken(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      Address recipient
  ) throws Exception {
    var transaction = MintTransaction.create(
        recipient,
        TokenId.generate(),
        TokenType.generate(),
        CborSerializer.encodeArray()
    );

    var certificationData = CertificationData.fromMintTransaction(transaction);

    var response = client.submitCertificationRequest(certificationData).get();
    if (response.getStatus() != CertificationStatus.SUCCESS) {
      throw new RuntimeException(
          String.format("Certification Request failed with status '%s'", response.getStatus()));
    }

    return Token.mint(
        trustBase,
        predicateVerifier,
        transaction.toCertifiedTransaction(
            trustBase,
            predicateVerifier,
            InclusionProofUtils.waitInclusionProof(client, trustBase, predicateVerifier, transaction).get()
        )
    );
  }


  public static Token transferToken(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      byte[] tokenBytes,
      Address recipient,
      SigningService signingService
  ) throws Exception {
    Token token = Token.fromCbor(tokenBytes);
    Assertions.assertEquals(VerificationStatus.OK, token.verify(trustBase, predicateVerifier).getStatus());

    byte[] x = new byte[32];
    new SecureRandom().nextBytes(x);

    var transaction = TransferTransaction.create(
        token,
        PayToPublicKeyPredicate.create(signingService.getPublicKey()),
        recipient,
        x,
        CborSerializer.encodeArray()
    );

    var response = client.submitCertificationRequest(
        CertificationData.fromTransaction(
            transaction,
            PayToPublicKeyPredicateUnlockScript.create(transaction, signingService).encode()
        )
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
