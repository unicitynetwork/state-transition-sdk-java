package org.unicitylabs.sdk.e2e.context;

import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.hash.DataHash;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenId;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.utils.TestUtils;
import org.unicitylabs.sdk.utils.helpers.AggregatorRequestHelper;
import org.unicitylabs.sdk.utils.helpers.CommitmentResult;
import org.unicitylabs.sdk.utils.helpers.PendingTransfer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Shared test context that maintains state across all step definition classes.
 * This allows different step definition classes to share data and avoid duplication.
 */
public class TestContext {

    // Core clients
    private AggregatorClient aggregatorClient;
    private TestAggregatorClient testAggregatorClient;
    private StateTransitionClient client;
    private RootTrustBase trustBase;


    // User management
    // Preserves the token as it was immediately after minting. Required for
    // double-spend testing: after a transfer the token object is consumed,
    // so we need the original to attempt a second spend.
    private Map<String, Token> originalMintedTokens = new HashMap<>();
    private Map<String, SigningService> userSigningServices = new HashMap<>();
    private Map<String, byte[]> userNonces = new HashMap<>();
    private Map<String, byte[]> userSecrets = new HashMap<>();
    private Map<String, Predicate> userPredicate = new HashMap<>();
    private Map<String, List<Token>> userTokens = new HashMap<>();
    private Map<String, List<Token>> nameTagTokens = new HashMap<>();
    private final Map<String, List<PendingTransfer>> pendingTransfers = new HashMap<>();
    private final Map<String, Map<TokenId, TokenId>> userNametagRelations = new HashMap<>();



    // Test execution state
    private Long blockHeight;
    private byte[] randomSecret;
    private byte[] stateBytes;
    private DataHash stateHash;
    private DataHash txDataHash;
    private SubmitCommitmentResponse commitmentResponse;
    private long submissionDuration;
    private Exception lastError;
    private boolean operationSucceeded;

    // Performance testing
    private int configuredThreadCount;
    private int configuredCommitmentsPerThread;
    private List<AggregatorClient> aggregatorClients;
    private List<Future<Boolean>> concurrentResults = new ArrayList<>();
    private long concurrentSubmissionDuration;
    private List<TestUtils.TokenOperationResult> bulkResults = new ArrayList<>();
    private long bulkOperationDuration;

    // Transfer chain tracking
    private List<String> transferChain = new ArrayList<>();
    private Token chainToken;
    private Map<String, String> transferCustomData = new HashMap<>();

    // Current operation context
    private String currentUser;
    private String expectedErrorType;
    private int expectedSplitCount;
    private int configuredUserCount;
    private int configuredTokensPerUser;
    private List<String> aggregatorUrls;
    private AggregatorRequestHelper shardHelper;

    public AggregatorRequestHelper getShardHelper() {
        return shardHelper;
    }

    public void setShardHelper(AggregatorRequestHelper shardHelper) {
        this.shardHelper = shardHelper;
    }

    public List<String> getAggregatorUrls() {
        return aggregatorUrls;
    }

    public void setAggregatorUrls(List<String> aggregatorUrls) {
        this.aggregatorUrls = aggregatorUrls;
    }


    // Getters and Setters
    public Map<String, Token> getOriginalMintedTokens() { return originalMintedTokens; }

    public AggregatorClient getAggregatorClient() { return aggregatorClient; }
    public void setAggregatorClient(AggregatorClient aggregatorClient) { this.aggregatorClient = aggregatorClient; }

    public RootTrustBase getTrustBase() { return trustBase; }
    public void setTrustBase(RootTrustBase trustBase) { this.trustBase = trustBase; }

    public TestAggregatorClient getTestAggregatorClient() { return testAggregatorClient; }
    public void setTestAggregatorClient(TestAggregatorClient testAggregatorClient) { this.testAggregatorClient = testAggregatorClient; }

    public StateTransitionClient getClient() { return client; }
    public void setClient(StateTransitionClient client) { this.client = client; }

    public Map<String, SigningService> getUserSigningServices() { return userSigningServices; }
    public void setUserSigningServices(Map<String, SigningService> userSigningServices) { this.userSigningServices = userSigningServices; }

    public Map<String, byte[]> getUserNonces() { return userNonces; }
    public void setUserNonces(Map<String, byte[]> userNonces) { this.userNonces = userNonces; }

    public Map<String, byte[]> getUserSecret() { return userSecrets; }
    public void setUserSecret(Map<String, byte[]> userSecrets) { this.userSecrets = userSecrets; }

    public Map<String, Predicate> getUserPredicate() {
        return userPredicate;
    }

    public void setUserPredicate(Map<String, Predicate> userPredicate) {
        this.userPredicate = userPredicate;
    }

    public List<AggregatorClient> getAggregatorClients() {
        return aggregatorClients;
    }

    public void setAggregatorClients(List<AggregatorClient> aggregatorClients) {
        this.aggregatorClients = aggregatorClients;
    }

    public Map<String, List<Token>> getUserTokens() { return userTokens; }
    public void setUserTokens(Map<String, List<Token>> userTokens) { this.userTokens = userTokens; }


    /**
     * Removes a token owned by a user using any of:
     * - the token object itself
     * - its index in the user's token list
     * - its token ID
     *
     * @param userName name of the user
     * @param identifier can be Token, Integer (index), or TokenId / String (id)
     * @return true if a token was removed, false otherwise
     */
    public boolean removeUserToken(String userName, Object identifier) {
        List<Token> tokens = userTokens.get(userName);
        if (tokens == null || tokens.isEmpty()) {
            return false;
        }

        // 1️⃣ Case: remove by token object
        if (identifier instanceof Token) {
            return tokens.remove((Token) identifier);
        }

        // 2️⃣ Case: remove by index (Integer)
        if (identifier instanceof Integer) {
            int index = (Integer) identifier;
            if (index >= 0 && index < tokens.size()) {
                tokens.remove(index);
                return true;
            }
            return false;
        }

        // 3️⃣ Case: remove by token ID (TokenId or String)
        if (identifier instanceof TokenId) {
            TokenId tokenId = (TokenId) identifier;
            return tokens.removeIf(t -> t.getId().equals(tokenId));
        }

        if (identifier instanceof String) {
            String tokenIdString = (String) identifier;
            return tokens.removeIf(t -> t.getId().toString().equals(tokenIdString));
        }

        // 4️⃣ Unknown identifier type
        return false;
    }

    public Map<String, List<Token>> getNameTagTokens() { return nameTagTokens; }
    public void setNameTagTokens(Map<String, List<Token>> nameTagTokens) { this.nameTagTokens = nameTagTokens; }

    public Long getBlockHeight() { return blockHeight; }
    public void setBlockHeight(Long blockHeight) { this.blockHeight = blockHeight; }

    public byte[] getRandomSecret() { return randomSecret; }
    public void setRandomSecret(byte[] randomSecret) { this.randomSecret = randomSecret; }

    public byte[] getStateBytes() { return stateBytes; }
    public void setStateBytes(byte[] stateBytes) { this.stateBytes = stateBytes; }

    public DataHash getStateHash() { return stateHash; }
    public void setStateHash(DataHash stateHash) { this.stateHash = stateHash; }

    public DataHash getTxDataHash() { return txDataHash; }
    public void setTxDataHash(DataHash txDataHash) { this.txDataHash = txDataHash; }

    public SubmitCommitmentResponse getCommitmentResponse() { return commitmentResponse; }
    public void setCommitmentResponse(SubmitCommitmentResponse commitmentResponse) { this.commitmentResponse = commitmentResponse; }

    public long getSubmissionDuration() { return submissionDuration; }
    public void setSubmissionDuration(long submissionDuration) { this.submissionDuration = submissionDuration; }

    public Exception getLastError() { return lastError; }
    public void setLastError(Exception lastError) { this.lastError = lastError; }

    public boolean isOperationSucceeded() { return operationSucceeded; }
    public void setOperationSucceeded(boolean operationSucceeded) { this.operationSucceeded = operationSucceeded; }

    public int getConfiguredThreadCount() { return configuredThreadCount; }
    public void setConfiguredThreadCount(int configuredThreadCount) { this.configuredThreadCount = configuredThreadCount; }

    public int getConfiguredCommitmentsPerThread() { return configuredCommitmentsPerThread; }
    public void setConfiguredCommitmentsPerThread(int configuredCommitmentsPerThread) { this.configuredCommitmentsPerThread = configuredCommitmentsPerThread; }

    public List<Future<Boolean>> getConcurrentResults() { return concurrentResults; }
    public void setConcurrentResults(List<Future<Boolean>> concurrentResults) { this.concurrentResults = concurrentResults; }

    public long getConcurrentSubmissionDuration() { return concurrentSubmissionDuration; }
    public void setConcurrentSubmissionDuration(long concurrentSubmissionDuration) { this.concurrentSubmissionDuration = concurrentSubmissionDuration; }

    public List<TestUtils.TokenOperationResult> getBulkResults() { return bulkResults; }
    public void setBulkResults(List<TestUtils.TokenOperationResult> bulkResults) { this.bulkResults = bulkResults; }

    public long getBulkOperationDuration() { return bulkOperationDuration; }
    public void setBulkOperationDuration(long bulkOperationDuration) { this.bulkOperationDuration = bulkOperationDuration; }

    public List<String> getTransferChain() { return transferChain; }
    public void setTransferChain(List<String> transferChain) { this.transferChain = transferChain; }

    public Token getChainToken() { return chainToken; }
    public void setChainToken(Token chainToken) { this.chainToken = chainToken; }

    public Map<String, String> getTransferCustomData() { return transferCustomData; }
    public void setTransferCustomData(Map<String, String> transferCustomData) { this.transferCustomData = transferCustomData; }

    public String getCurrentUser() { return currentUser; }
    public void setCurrentUser(String currentUser) { this.currentUser = currentUser; }

    public String getExpectedErrorType() { return expectedErrorType; }
    public void setExpectedErrorType(String expectedErrorType) { this.expectedErrorType = expectedErrorType; }

    public int getExpectedSplitCount() { return expectedSplitCount; }
    public void setExpectedSplitCount(int expectedSplitCount) { this.expectedSplitCount = expectedSplitCount; }

    public int getConfiguredUserCount() { return configuredUserCount; }
    public void setConfiguredUserCount(int configuredUserCount) { this.configuredUserCount = configuredUserCount; }

    public int getConfiguredTokensPerUser() { return configuredTokensPerUser; }
    public void setConfiguredTokensPerUser(int configuredTokensPerUser) { this.configuredTokensPerUser = configuredTokensPerUser; }

    public void savePendingTransfer(String user, Token<?> token, TransferTransaction tx) {
        pendingTransfers.computeIfAbsent(user, k -> new ArrayList<>())
                .add(new PendingTransfer(token, tx));
    }

    public List<PendingTransfer> getPendingTransfers(String user) {
        return pendingTransfers.getOrDefault(user, List.of());
    }

    public void clearPendingTransfers(String user) {
        pendingTransfers.remove(user);
    }


    // Utility methods
    public void addUserToken(String userName, Token token) {
        userTokens.computeIfAbsent(userName, k -> new ArrayList<>()).add(token);
    }

    public Token getUserToken(String userName) {
        List<Token> tokens = userTokens.get(userName);
        return (tokens != null && !tokens.isEmpty()) ? tokens.get(0) : null;
    }

    public Token getUserToken(String userName, int index) {
        List<Token> tokens = userTokens.get(userName);
        return (tokens != null && tokens.size() > index) ? tokens.get(index) : null;
    }

    public void addNameTagToken(String userName, Token nameTagToken) {
        nameTagTokens.computeIfAbsent(userName, k -> new ArrayList<>()).add(nameTagToken);
    }

    public Token getNameTagToken(String userName) {
        List<Token> tokens = nameTagTokens.get(userName);
        return (tokens != null && !tokens.isEmpty()) ? tokens.get(0) : null;
    }

    public void addNametagRelation(String username, TokenId nametagId, TokenId originalTokenId) {
        userNametagRelations
                .computeIfAbsent(username, k -> new HashMap<>())
                .put(nametagId, originalTokenId);
    }

    public TokenId getOriginalTokenIdForNametag(String username, TokenId nametagId) {
        Map<TokenId, TokenId> relations = userNametagRelations.get(username);
        if (relations == null) return null;
        return relations.get(nametagId);
    }

    public Map<TokenId, TokenId> getNametagRelationsForUser(String username) {
        return userNametagRelations.getOrDefault(username, new HashMap<>());
    }

    public void setNametagRelationsForUser(String username, Map<TokenId, TokenId> relations) {
        userNametagRelations.put(username, relations);
    }

    public Map<String, Map<TokenId, TokenId>> getAllNametagRelations() {
        return userNametagRelations;
    }

    public void clearUserData() {
        userSigningServices.clear();
        userNonces.clear();
        userSecrets.clear();
        userTokens.clear();
        nameTagTokens.clear();
        userNametagRelations.clear();
    }

    public void clearTestState() {
        originalMintedTokens.clear();
        aggregatorUrls = null;
        configuredUserCount = 0;
        blockHeight = null;
        randomSecret = null;
        stateBytes = null;
        stateHash = null;
        txDataHash = null;
        commitmentResponse = null;
        submissionDuration = 0;
        lastError = null;
        operationSucceeded = false;
        concurrentResults.clear();
        bulkResults.clear();
        transferChain.clear();
        chainToken = null;
        transferCustomData.clear();
        currentUser = null;
        expectedErrorType = null;
        nameTagTokens.clear();
        pendingTransfers.clear();
        userNametagRelations.clear();
    }

    public void reset() {
        clearUserData();
        clearTestState();
        aggregatorClient = null;
        testAggregatorClient = null;
        client = null;
        trustBase = null;
    }

    private List<CompletableFuture<CommitmentResult>> commitmentFutures = new ArrayList<>();

    public void setCommitmentFutures(List<CompletableFuture<CommitmentResult>> futures) {
        this.commitmentFutures = futures;
    }

    public List<CompletableFuture<CommitmentResult>> getCommitmentFutures() {
        return commitmentFutures;
    }
}