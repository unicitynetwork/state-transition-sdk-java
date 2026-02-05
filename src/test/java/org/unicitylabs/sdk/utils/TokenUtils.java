package org.unicitylabs.sdk.utils;

import static org.unicitylabs.sdk.utils.TestUtils.randomBytes;
import static org.unicitylabs.sdk.utils.TestUtils.randomCoinData;

import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.address.Address;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.hash.DataHash;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicateReference;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenId;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import org.unicitylabs.sdk.transaction.*;
import org.unicitylabs.sdk.util.InclusionProofUtils;

public class TokenUtils {

  public static Token<?> mintToken(
      StateTransitionClient client,
      RootTrustBase trustBase,
      byte[] secret
  ) throws Exception {
    return TokenUtils.mintToken(
        client,
        trustBase,
        secret,
        new TokenId(randomBytes(32)),
        new TokenType(randomBytes(32)),
        randomBytes(32),
        randomCoinData(2),
        randomBytes(32),
        randomBytes(32),
        null
    );
  }

  public static Token<?> mintToken(
      StateTransitionClient client,
      RootTrustBase trustBase,
      byte[] secret,
      TokenId tokenId,
      TokenType tokenType,
      byte[] tokenData,
      TokenCoinData coinData,
      byte[] nonce,
      byte[] salt,
      DataHash dataHash
  ) throws Exception {
    SigningService signingService = SigningService.createFromMaskedSecret(secret, nonce);

    MaskedPredicate predicate = MaskedPredicate.create(
        tokenId,
        tokenType,
        signingService,
        HashAlgorithm.SHA256,
        nonce
    );

    Address address = predicate.getReference().toAddress();
    TokenState tokenState = new TokenState(predicate, null);

    MintTransaction.Data<?>  transactionData = new MintTransaction.Data<>(
            tokenId,
            tokenType,
            tokenData,
            coinData,
            address,
            salt,
            dataHash,
            null
    );

    MintCommitment<?> commitment = MintCommitment.create(
            transactionData
    );

    SubmitCommitmentResponse response = client
        .submitCommitment(commitment)
        .get();
    if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
      throw new Exception(String.format("Failed to submit mint commitment: %s",
          response.getStatus()));
    }

    // Wait for inclusion proof
    InclusionProof inclusionProof = InclusionProofUtils.waitInclusionProof(
        client,
        trustBase,
        commitment
    ).get();

    // Create mint transaction
    return Token.create(
        trustBase,
        tokenState,
        commitment.toTransaction(inclusionProof)
    );
  }

  public static Token<?> mintNametagToken(
      StateTransitionClient client,
      RootTrustBase trustBase,
      byte[] secret,
      String nametag,
      Address targetAddress
  ) throws Exception {
    return mintNametagToken(
        client,
        trustBase,
        secret,
        new TokenType(randomBytes(32)),
        nametag,
        targetAddress,
        randomBytes(32),
        randomBytes(32)
    );
  }

  public static Token<?> mintNametagToken(
      StateTransitionClient client,
      RootTrustBase trustBase,
      byte[] secret,
      TokenType tokenType,
      String nametag,
      Address targetAddress,
      byte[] nonce,
      byte[] salt
  ) throws Exception {
    SigningService signingService = SigningService.createFromMaskedSecret(secret, nonce);

    Address address = MaskedPredicateReference.create(
        tokenType,
        signingService,
        HashAlgorithm.SHA256,
        nonce).toAddress();

    MintCommitment<?> commitment = MintCommitment.create(
        new MintTransaction.NametagData(
            nametag,
            tokenType,
            address,
            salt,
            targetAddress
        )
    );

    // Submit mint transaction using StateTransitionClient
    SubmitCommitmentResponse response = client
        .submitCommitment(commitment)
        .get();
    if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
      throw new Exception(String.format("Failed to submit mint commitment: %s",
          response.getStatus()));
    }

    // Wait for inclusion proof
    InclusionProof inclusionProof = InclusionProofUtils.waitInclusionProof(
        client,
        trustBase,
        commitment
    ).get();

    // Create mint transaction
    return Token.create(
        trustBase,
        new TokenState(
            MaskedPredicate.create(
                commitment.getTransactionData().getTokenId(),
                commitment.getTransactionData().getTokenType(),
                signingService,
                HashAlgorithm.SHA256,
                nonce
            ),
            null
        ),
        commitment.toTransaction(inclusionProof)
    );
  }
}