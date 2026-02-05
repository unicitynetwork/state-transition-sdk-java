package org.unicitylabs.sdk.utils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;

import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.address.Address;
import org.unicitylabs.sdk.api.Authenticator;
import org.unicitylabs.sdk.api.RequestId;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.hash.DataHash;
import org.unicitylabs.sdk.hash.DataHasher;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.PredicateEngineService;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenId;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.token.fungible.CoinId;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import org.unicitylabs.sdk.transaction.*;
import org.unicitylabs.sdk.util.InclusionProofUtils;

/**
 * Utility methods for tests.
 */
public class TestUtils {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String FIXED_TOKEN_TYPE_HEX = "f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509";
    private static final String FIXED_COIN_ID_HEX   = "455ad8720656b08e8dbd5bac1f3c73eeea5431565f6c1c3af742b1aa12d41d89";

    /**
     * Converts a hex string to bytes.
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte)
                    ((Character.digit(hex.charAt(i), 16) << 4)
                            + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Returns a fixed TokenType using the given HEX constant.
     */
    public static TokenType fixedTokenType() {
        return new TokenType(hexToBytes(FIXED_TOKEN_TYPE_HEX));
    }

    /**
     * Returns a fixed CoinId using the given HEX constant.
     */
    public static CoinId fixedCoinId() {
        return new CoinId(hexToBytes(FIXED_COIN_ID_HEX));
    }

    /**
     * Creates TokenCoinData with a fixed CoinId and specified amount.
     * @param amount Coin amount (BigInteger)
     */
    public static TokenCoinData fixedCoinData(BigInteger amount) {
        Map<CoinId, BigInteger> coins = Collections.singletonMap(fixedCoinId(), amount);
        return new TokenCoinData(coins);
    }

    /**
     * Convenience overload to specify amount as long.
     */
    public static TokenCoinData fixedCoinData(long amount) {
        return fixedCoinData(BigInteger.valueOf(amount));
    }

    /**
     * Generate random bytes of specified length.
     */
    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
    
    /**
     * Generate a random coin amount between 10 and 99.
     */
    public static BigInteger randomCoinAmount() {
        return BigInteger.valueOf(10 + RANDOM.nextInt(90));
    }
    
    /**
     * Create random coin data with specified number of coins.
     */
    public static TokenCoinData randomCoinData(int numCoins) {
        Map<CoinId, BigInteger> coins = new java.util.HashMap<>();
        for (int i = 0; i < numCoins; i++) {
            coins.put(new CoinId(randomBytes(32)), randomCoinAmount());
        }
        return new TokenCoinData(coins);
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Creates a token mint commitment and submits it to the client
     */
    public static Token mintTokenForUser(
            StateTransitionClient client,
            SigningService signingService,
            byte[] nonce,
            TokenId tokenId,
            TokenType tokenType,
            TokenCoinData coinData,
            RootTrustBase trustBase
            ) throws Exception {

        MaskedPredicate predicate = MaskedPredicate.create(tokenId, tokenType, signingService, HashAlgorithm.SHA256, nonce);
        Address address = predicate.getReference().toAddress();
        TokenState tokenState = new TokenState(predicate, null);

        MintCommitment<?> mintCommitment = MintCommitment.create(
                new MintTransaction.Data<>(
                        tokenId,
                        tokenType,
                        new TestTokenData(randomBytes(32)).getData(),
                        coinData,
                        address,
                        randomBytes(5),
                        null,
                        null
                )
        );

        SubmitCommitmentResponse response = client.submitCommitment(mintCommitment).get();
        if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
            throw new Exception("Failed to submit mint commitment: " + response.getStatus());
        }

        InclusionProof inclusionProof = InclusionProofUtils
                .waitInclusionProof(
                        client,
                        trustBase,
                        mintCommitment
                ).get();
        return Token.create(
                trustBase,
                tokenState,
                mintCommitment.toTransaction(inclusionProof)
        );
    }

    /**
     * Transfers a token from one user to another
     */
    public static Token transferToken(
            StateTransitionClient client,
            Token sourceToken,
            SigningService fromSigningService,
            SigningService toSigningService,
            byte[] toNonce,
            Address toAddress,
            byte[] customData,
            List<Token<?>> additionalTokens,
            RootTrustBase trustBase
            ) throws Exception {

        // Create data hash if custom data provided
        DataHash dataHash = null;
        if (customData != null) {
            dataHash = hashData(customData);
        }

        // Submit transfer commitment
        TransferCommitment transferCommitment = TransferCommitment.create(
                sourceToken,
                toAddress,
                randomBytes(32),
                dataHash,
                null,
                fromSigningService
        );

        SubmitCommitmentResponse response = client.submitCommitment(transferCommitment).get();
        if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
            throw new Exception("Failed to submit transfer commitment: " + response.getStatus());
        }

        // Wait for inclusion proof
        InclusionProof inclusionProof = InclusionProofUtils.waitInclusionProof(client, trustBase, transferCommitment).get();
        TransferTransaction transferTransaction = transferCommitment.toTransaction(inclusionProof);

        // Create predicate for recipient
        MaskedPredicate toPredicate = MaskedPredicate
                .create(
                    sourceToken.getId(),
                    sourceToken.getType(),
                    toSigningService,
                        HashAlgorithm.SHA256,
                        toNonce
                );

        // Finalize transaction
        return client.finalizeTransaction(
                trustBase,
                sourceToken,
                new TokenState(toPredicate, customData),
                transferTransaction
        );
    }

    /**
     * Creates random coin data with specified number of coins.
     */
    public static TokenCoinData createRandomCoinData(int coinCount) {
        return randomCoinData(coinCount);
    }

    public static TokenCoinData createCoinDataFromTable(List<Map<String, String>> coinRows) {
        Map<CoinId, BigInteger> coins = new HashMap<>();

        for (Map<String, String> row : coinRows) {
            String idHex = row.get("id");
            String valueStr = row.get("value");

            // Convert hex ID → CoinId
            CoinId coinId = new CoinId(idHex.getBytes(StandardCharsets.UTF_8));

            // Convert value → BigInteger
            BigInteger value = new BigInteger(valueStr);

            coins.put(coinId, value);
        }

        return new TokenCoinData(coins);
    }

    /**
     * Generates random bytes of specified length.
     */
    public static byte[] generateRandomBytes(int length) {
        return randomBytes(length);
    }

    /**
     * Creates a hash of the provided data
     */
    public static DataHash hashData(byte[] data) {
        return new DataHasher(HashAlgorithm.SHA256).update(data).digest();
    }

    /**
     * Creates a hash of string data
     */
    public static DataHash hashData(String data) {
        return hashData(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a signing service from a user name and optional nonce
     */
    public static SigningService createSigningServiceForUser(String userName, byte[] nonce) {
        byte[] secret = userName.getBytes(StandardCharsets.UTF_8);
        return SigningService.createFromMaskedSecret(secret, nonce);
    }

    /**
     * Sets up a user with signing service and nonce in the provided maps
     */
    public static void setupUser(String userName,
                                 Map<String, SigningService> userSigningServices,
                                 Map<String, byte[]> userNonces,
                                 Map<String, byte[]> userSecret) {
        byte[] secret = userName.getBytes(StandardCharsets.UTF_8);
        byte[] nonce = generateRandomBytes(32);
        SigningService signingService = SigningService.createFromMaskedSecret(secret, nonce);

        userSigningServices.put(userName, signingService);
        userNonces.put(userName, nonce);
        userSecret.put(userName,secret);
    }

    /**
     * Validates that a token is properly owned by a signing service
     */
    public static boolean validateTokenOwnership(Token token, SigningService signingService, RootTrustBase trustBase) {
        if (!token.verify(trustBase).isSuccessful()) {
            return false;
        }
        return PredicateEngineService.createPredicate(token.getState().getPredicate()).isOwner(signingService.getPublicKey());
    }

    public static RequestId createRequestId(SigningService signingService, DataHash stateHash) {
        return RequestId.create(signingService.getPublicKey(), stateHash);
    }

    public static Authenticator createAuthenticator(SigningService signingService, DataHash txDataHash, DataHash stateHash) {
        return Authenticator.create(signingService, txDataHash, stateHash);
    }

    /**
     * Generates a random token ID
     */
    public static TokenId generateRandomTokenId() {
        return new TokenId(randomBytes(32));
    }

    /**
     * Generates a random token type
     */
    public static TokenType generateRandomTokenType() {
        return new TokenType(randomBytes(32));
    }

    /**
     * Creates a token type from a string identifier
     */
    public static TokenType createTokenTypeFromString(String identifier) {
        return new TokenType(identifier.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validates performance metrics
     */
    public static class PerformanceValidator {
        public static void validateDuration(long actualDuration, long maxDuration, String operation) {
            if (actualDuration >= maxDuration) {
                throw new AssertionError(String.format(
                        "%s took %d ms, should be less than %d ms",
                        operation, actualDuration, maxDuration));
            }
        }

        public static void validateSuccessRate(long successful, long total, double minSuccessRate, String operation) {
            double actualRate = (double) successful / total;
            if (actualRate < minSuccessRate) {
                throw new AssertionError(String.format(
                        "%s success rate %.2f%% is below required %.2f%%",
                        operation, actualRate * 100, minSuccessRate * 100));
            }
        }
    }

    /**
     * Token operation result wrapper
     */
    public static class TokenOperationResult {
        private final boolean success;
        private final String message;
        private final Token token;
        private final Exception error;

        public TokenOperationResult(boolean success, String message, Token token, Exception error) {
            this.success = success;
            this.message = message;
            this.token = token;
            this.error = error;
        }

        public static TokenOperationResult success(String message, Token token) {
            return new TokenOperationResult(true, message, token, null);
        }

        public static TokenOperationResult failure(String message, Exception error) {
            return new TokenOperationResult(false, message, null, error);
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public Token getToken() { return token; }
        public Exception getError() { return error; }
    }

    public static String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }


}