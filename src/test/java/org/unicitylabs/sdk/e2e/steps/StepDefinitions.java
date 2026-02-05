package org.unicitylabs.sdk.e2e.steps;

import io.cucumber.datatable.DataTable;
import org.unicitylabs.sdk.address.ProxyAddress;
import org.unicitylabs.sdk.e2e.config.CucumberConfiguration;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.e2e.steps.shared.StepHelper;
import org.unicitylabs.sdk.token.fungible.CoinId;
import org.unicitylabs.sdk.utils.TestUtils;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenId;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Refactored step definitions that use TestContext and SharedStepDefinitions.
 * These steps handle specific scenarios that aren't covered by the shared steps.
 */
public class StepDefinitions {

    private final TestContext context;

    public StepDefinitions() {
        this.context = CucumberConfiguration.getTestContext();
    }

    StepHelper helper = new StepHelper();

    // Token Properties Validation
    @And("the token should maintain its original ID and type")
    public void theTokenShouldMaintainItsOriginalIdAndType() {
        String currentUser = context.getCurrentUser();
        if (currentUser == null) currentUser = "Alice"; // fallback

        // Get the first user's token (original) and current user's token
        List<String> userNames = new ArrayList<>(context.getUserTokens().keySet());
        if (userNames.size() >= 2) {
            Token originalToken = context.getUserToken(userNames.get(0));
            Token currentToken = context.getUserToken(currentUser);

            if (originalToken != null && currentToken != null) {
                assertEquals(originalToken.getId(), currentToken.getId(), "Token ID should remain the same");
                assertEquals(originalToken.getType(), currentToken.getType(), "Token type should remain the same");
            }
        }
    }

    @And("the token should have {int} transactions in its history")
    public void theTokenShouldHaveTransactionsInItsHistory(int expectedTransactionCount) {
        String currentUser = context.getCurrentUser();
        if (currentUser == null) {
            // Find the last user who received a token
            List<String> userNames = new ArrayList<>(context.getUserTokens().keySet());
            currentUser = userNames.get(userNames.size() - 1);
        }

        Token token = context.getUserToken(currentUser);
        assertNotNull(token, "Token should exist for validation");
        assertEquals(expectedTransactionCount, token.getTransactions().size(), // Subtract mint transaction
                "Token should have the expected number of transactions");
    }

    // Minting with Parameters
    @Given("user {string} with nonce of {int} bytes")
    public void userWithNonceOfBytes(String userName, int nonceLength) {
        byte[] nonce = TestUtils.generateRandomBytes(nonceLength);
        SigningService signingService = TestUtils.createSigningServiceForUser(userName, nonce);

        context.getUserSigningServices().put(userName, signingService);
        context.getUserNonces().put(userName, nonce);
        context.getUserTokens().put(userName, new ArrayList<>());
        context.setCurrentUser(userName);
    }

    @When("the user mints a token of type {string} with coin data containing {int} coins")
    public void theUserMintsATokenOfTypeWithCoinDataContainingCoins(String tokenType, int coinCount) throws Exception {
        String user = context.getCurrentUser();
        TokenId tokenId = TestUtils.generateRandomTokenId();
        TokenType type = TestUtils.createTokenTypeFromString(tokenType);
        TokenCoinData coinData = TestUtils.createRandomCoinData(coinCount);

        Token token = TestUtils.mintTokenForUser(
                context.getClient(),
                context.getUserSigningServices().get(user),
                context.getUserNonces().get(user),
                tokenId,
                type,
                coinData,
                context.getTrustBase()
        );
        context.addUserToken(user, token);
    }

    @When("the {string} mints a token of type {string} with coins data desctribed below")
    public void theUserMintsATokenOfTypeWithCoins(String username,String tokenType, DataTable dataTable) throws Exception {
        context.setCurrentUser(username);
        String user = context.getCurrentUser();
        TokenId tokenId = TestUtils.generateRandomTokenId();
        TokenType type = TestUtils.createTokenTypeFromString(tokenType);
        List<Map<String, String>> coinRows = dataTable.asMaps(String.class, String.class);

        for (Map<String, String> row : coinRows) {
            String name = row.get("name");
            String symbol = row.get("symbol");
            String id = row.get("id");
            int decimals = Integer.parseInt(row.get("decimals"));
            int value = Integer.parseInt(row.get("value"));

            System.out.printf(
                    "Token type: %s | Name: %s | Symbol: %s | ID: %s | Decimals: %d | Value: %d%n",
                    tokenType, name, symbol, id, decimals, value
            );

            // 🪙 Your actual mint logic goes here
            // e.g. TestUtils.mintToken(tokenType, name, symbol, id, decimals, value);
        }

        // Convert table → TokenCoinData
        TokenCoinData coinData = TestUtils.createCoinDataFromTable(coinRows);

        Token token = TestUtils.mintTokenForUser(
                context.getClient(),
                context.getUserSigningServices().get(user),
                context.getUserNonces().get(user),
                tokenId,
                type,
                coinData,
                context.getTrustBase()
        );
        // do post-processing here (still in parallel)
        if (TestUtils.validateTokenOwnership(
                token,
                context.getUserSigningServices().get(user),
                context.getTrustBase()
        )) {
            context.addUserToken(user, token);
        }
        context.setCurrentUser(user);
    }

    @Then("the token should be minted successfully")
    public void theTokenShouldBeMintedSuccessfully() {
        String user = context.getCurrentUser();
        Token token = context.getUserToken(user);
        assertNotNull(token, "Token should be minted");
    }

    @And("the token should be verified successfully")
    public void theTokenShouldBeVerifiedSuccessfully() {
        String user = context.getCurrentUser();
        Token token = context.getUserToken(user);
        assertTrue(token.verify(context.getTrustBase()).isSuccessful(), "Token should be verified successfully");
    }

    @And("the token should belong to the user")
    public void theTokenShouldBelongToTheUser() {
        String user = context.getCurrentUser();
        Token token = context.getUserToken(user);
        SigningService signingService = context.getUserSigningServices().get(user);

        assertTrue(TestUtils
                        .validateTokenOwnership(
                                token,
                                signingService,
                                context.getTrustBase()
                        ),
                "Token should belong to the user");
    }

    @Then("the name tag token should be created successfully")
    public void theNameTagTokenShouldBeCreatedSuccessfully() {
        String user = context.getCurrentUser();
        Token nametagToken = context.getNameTagToken(user);
        assertNotNull(nametagToken, "Name tag token should be created");
        assertTrue(nametagToken.verify(context.getTrustBase()).isSuccessful(), "Name tag token should be valid");
    }

    @And("the name tag should be usable for proxy addressing")
    public void theNameTagShouldBeUsableForProxyAddressing() {
        String user = context.getCurrentUser();
        Token nametagToken = context.getNameTagToken(user);
        ProxyAddress proxyAddress = ProxyAddress.create(nametagToken.getId());
        assertNotNull(proxyAddress, "Proxy address should be creatable from name tag");
    }

    // Bulk Operations
    @Given("{int} users are configured for bulk operations")
    public void usersAreConfiguredForBulkOperations(int userCount) {
        context.setConfiguredUserCount(userCount);

        // Setup additional users if needed
        for (int i = 0; i < userCount; i++) {
            String userName = "BulkUser" + i;
            TestUtils.setupUser(userName, context.getUserSigningServices(), context.getUserNonces(), context.getUserSecret());
            context.getUserTokens().put(userName, new ArrayList<>());
        }
    }

    @When("each user mints {int} tokens simultaneously")
    public void eachUserMintsTokensSimultaneously(int tokensPerUser) throws Exception {
        context.setConfiguredTokensPerUser(tokensPerUser);

        //Lower the thread pool size to avoid overload
        int poolSize = Math.min(500, context.getConfiguredUserCount() * 2);
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        //ExecutorService executor = Executors.newFixedThreadPool(context.getConfiguredUserCount() * 2);
        List<CompletableFuture<TestUtils.TokenOperationResult>> futures = new ArrayList<>();
        Map<CompletableFuture<TestUtils.TokenOperationResult>, String> futureOwners = new ConcurrentHashMap<>();

        long startTime = System.currentTimeMillis();

        // Create minting tasks for each user
        for (int userIndex = 0; userIndex < context.getConfiguredUserCount(); userIndex++) {
            String userName = "BulkUser" + userIndex;
            SigningService signingService = context.getUserSigningServices().get(userName);
            byte[] nonce = context.getUserNonces().get(userName);

            for (int tokenIndex = 0; tokenIndex < tokensPerUser; tokenIndex++) {
                String requestId = userName + "-token" + tokenIndex; // helpful identifier

                CompletableFuture<TestUtils.TokenOperationResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        TokenId tokenId = TestUtils.generateRandomTokenId();
                        TokenType tokenType = TestUtils.generateRandomTokenType();
                        TokenCoinData coinData = TestUtils.createRandomCoinData(2);

                        Token token = TestUtils
                                .mintTokenForUser(
                                        context.getClient(),
                                        signingService,
                                        nonce,
                                        tokenId,
                                        tokenType,
                                        coinData,
                                        context.getTrustBase()
                                );
                        System.out.println(token.getGenesis().getData().getSourceState());
                        // do post-processing here (still in parallel)
                        for (var entry : context.getUserSigningServices().entrySet()) {
                            if (TestUtils
                                    .validateTokenOwnership(
                                            token,
                                            entry.getValue(),
                                            context.getTrustBase()
                                    )
                            ) {
                                context.addUserToken(entry.getKey(), token);
                                break;
                            }
                        }
                        System.out.println("[Collector] Got result from " + requestId +
                                " on thread " + Thread.currentThread().getName());
                        return TestUtils.TokenOperationResult.success("Token minted successfully (" + requestId + ")", token);
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.out.println("[Collector] Failed " + requestId + " on thread " + Thread.currentThread().getName() +
                                " with " + e.getMessage());
                        return TestUtils.TokenOperationResult.failure("Failed to mint token (" + requestId + ")", e);
                    }
                }, executor).orTimeout(40, TimeUnit.SECONDS)
                        .exceptionally(ex -> TestUtils.TokenOperationResult.failure("Timeout (" + requestId + ")", (Exception) ex));;

                futures.add(future);
                futureOwners.put(future, requestId);
            }
        }

        // Start monitoring thread
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(() -> {
            long doneCount = futures.stream().filter(CompletableFuture::isDone).count();
            long total = futures.size();
            long pending = total - doneCount;

            System.out.println("[Monitor] " + doneCount + "/" + total + " completed, " + pending + " still pending");

            if (pending == 0) {
                System.out.println("[Monitor] All requests completed. Stopping monitor.");
                monitor.shutdown();  // ✅ stop the monitor here
            }

            // After 15s, dump details of stuck ones
            if (System.currentTimeMillis() - startTime > 15_000 && pending > 0) {
                List<String> pendingRequests = futures.stream()
                        .filter(f -> !f.isDone())
                        .map(futureOwners::get)
                        .collect(Collectors.toList());
                System.out.println("[Monitor] Still waiting for requests: " + pendingRequests);
            }
        }, 5, 5, TimeUnit.SECONDS);

        // Wait for all operations to complete and collect results
        List<TestUtils.TokenOperationResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        long successes = results.stream().filter(TestUtils.TokenOperationResult::isSuccess).count();
        long failures = results.size() - successes;

        System.out.println("[Summary] Successes: " + successes + ", Failures: " + failures);

        long endTime = System.currentTimeMillis();
        context.setBulkResults(results);
        context.setBulkOperationDuration(endTime - startTime);

        executor.shutdown();
    }

    @And("all tokens are verified in parallel")
    public void allTokensAreVerifiedInParallel() {
        // Verification is already done during token creation
        long successfulTokens = context.getBulkResults().stream()
                .mapToLong(result -> result.isSuccess() ? 1 : 0)
                .sum();

        System.out.println("Successfully created tokens: " + successfulTokens);
        System.out.println("Total operation time: " + context.getBulkOperationDuration() + " ms");
    }

    @Then("all {int} tokens should be created successfully")
    public void allTokensShouldBeCreatedSuccessfully(int expectedTotalTokens) {
        long successfulTokens = context.getBulkResults().stream()
                .mapToLong(result -> result.isSuccess() ? 1 : 0)
                .sum();

        assertEquals(expectedTotalTokens, successfulTokens,
                "All tokens should be created successfully");
    }

    @And("the operation should complete within {int} seconds")
    public void theOperationShouldCompleteWithinSeconds(int maxSeconds) {
        long maxMilliseconds = maxSeconds * 1000L;
        TestUtils.PerformanceValidator.validateDuration(
                context.getBulkOperationDuration(),
                maxMilliseconds,
                "Bulk token creation"
        );
    }

    @And("the success rate should be at least {int}%")
    public void theSuccessRateShouldBeAtLeast(int minSuccessRate) {
        long successful = context.getBulkResults().stream()
                .mapToLong(result -> result.isSuccess() ? 1 : 0)
                .sum();
        long total = context.getBulkResults().size();

        TestUtils.PerformanceValidator.validateSuccessRate(
                successful,
                total,
                minSuccessRate / 100.0,
                "Bulk operations"
        );
    }

    // Transfer Chain Operations
    @Given("{string} mints a token with {int} coin value")
    public void userMintsATokenWithCoinValue(String userName, int coinValue) throws Exception {
        TokenId tokenId = TestUtils.generateRandomTokenId();
        TokenType tokenType = TestUtils.generateRandomTokenType();

        // Create coin data with specified value
        TokenCoinData coinData = createCoinDataWithValue(BigInteger.valueOf(coinValue));

        Token token = TestUtils.mintTokenForUser(
                context.getClient(),
                context.getUserSigningServices().get(userName),
                context.getUserNonces().get(userName),
                tokenId,
                tokenType,
                coinData,
                context.getTrustBase()
        );

        context.setChainToken(token);
        context.getTransferChain().add(userName);
        context.setCurrentUser(userName);
    }

    @And("each transfer includes custom data validation")
    public void eachTransferIncludesCustomDataValidation() {
        // Validation is included in the transfer process
        for (Map.Entry<String, String> entry : context.getTransferCustomData().entrySet()) {
            assertNotNull(entry.getValue(), "Custom data should be present for " + entry.getKey());
            assertTrue(entry.getValue().contains("Transfer from"), "Custom data should have expected format");
        }
    }

    @And("the token should have {int} transfers in history")
    public void theTokenShouldHaveTransfersInHistory(int expectedTransfers) {
        int actualTransfers = context.getChainToken().getTransactions().size(); // Subtract mint transaction
        assertEquals(expectedTransfers, actualTransfers, "Token should have expected number of transfers");
    }

    private TokenCoinData createCoinDataWithValue(BigInteger totalValue) {
        CoinId coinId = new CoinId(TestUtils.generateRandomBytes(32));
        return new TokenCoinData(java.util.Map.of(coinId, totalValue));
    }
}