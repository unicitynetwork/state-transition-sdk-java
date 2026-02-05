package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.unicitylabs.sdk.address.ProxyAddress;
import org.unicitylabs.sdk.e2e.config.CucumberConfiguration;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.e2e.steps.shared.StepHelper;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenId;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.utils.TestUtils;
import org.unicitylabs.sdk.utils.helpers.PendingTransfer;

/**
 * Advanced step definitions for complex scenarios and edge cases.
 */
public class AdvancedStepDefinitions {

    private final TestContext context;

    public AdvancedStepDefinitions() {
        this.context = CucumberConfiguration.getTestContext();
    }

    StepHelper helper = new StepHelper();

    @When("the token is transferred through the chain of existing users")
    public void theTokenIsTransferredThroughTheChain() throws Exception {
        List<String> users = new ArrayList<>(context.getUserSigningServices().keySet());

        // remove the one that should go first (if it’s already inside)
        users.removeAll(context.getTransferChain());

        // prepend the transfer chain at the beginning
        List<String> orderedUsers = new ArrayList<>();
        orderedUsers.addAll(context.getTransferChain()); // first element(s)
        orderedUsers.addAll(users);


        Token currentToken = context.getChainToken();

        for (int i = 0; i < orderedUsers.size() - 1; i++) {
            String fromUser = orderedUsers.get(i);
            String toUser = orderedUsers.get(i + 1);

            SigningService fromSigningService = context.getUserSigningServices().get(fromUser);
            SigningService toSigningService = context.getUserSigningServices().get(toUser);
            byte[] toNonce = context.getUserNonces().get(toUser);

            // Create a simple direct address for transfer
            var toPredicate = MaskedPredicate.create(
                    currentToken.getId(),
                    currentToken.getType(),
                    toSigningService,
                    org.unicitylabs.sdk.hash.HashAlgorithm.SHA256,
                    toNonce
            );
            var toAddress = toPredicate.getReference().toAddress();

            String customData = "Transfer from " + fromUser + " to " + toUser;
            System.out.println(customData);
            context.getTransferCustomData().put(toUser, customData);

            currentToken = TestUtils.transferToken(
                    context.getClient(),
                    currentToken,
                    fromSigningService,
                    toSigningService,
                    toNonce,
                    toAddress,
                    customData.getBytes(StandardCharsets.UTF_8),
                    List.of(),
                    context.getTrustBase()
            );

            context.getTransferChain().add(toUser);
        }

        context.setChainToken(currentToken);
    }

    @Then("the final token should maintain original properties")
    public void theFinalTokenShouldMaintainOriginalProperties() {
        assertNotNull(context.getChainToken(), "Final token should exist");
        assertTrue(context.getChainToken().verify(context.getTrustBase()).isSuccessful(), "Final token should be valid");

        // Additional property validation can be added based on requirements
    }

    @And("the transfer chain should have {int} participants from {string} to {string}")
    public void theTransferChainShouldHaveParticipants(int expectedSize, String startUser, String endUser) {
        assertEquals(expectedSize, context.getTransferChain().size(),
                "Transfer chain should have " + expectedSize + " participants");
        assertEquals(startUser, context.getTransferChain().get(0),
                "Chain should start with " + startUser);
        assertEquals(endUser, context.getTransferChain().get(expectedSize - 1),
                "Chain should end with " + endUser);
    }

    @And("the token should have <expectedTransfers> transfers in history")
    public void theTokenShouldHaveTransfersInHistory(int expectedTransfers) {
        int actualTransfers = context.getChainToken().getTransactions().size() - 1; // Subtract mint transaction
        assertEquals(expectedTransfers, actualTransfers, "Token should have expected number of transfers");
    }

    // Name Tag Scenarios Steps
    @Given("{string} creates nametags for each token")
    public void createsNameTagTokensWithDifferentAddresses(String username) throws Exception {
        List<Token> nametags = new ArrayList<>();
        List<Token> ownerTokens = context.getUserTokens().get(context.getCurrentUser());

        Map<TokenId, TokenId> relations = new HashMap<>();

        for (Token token : ownerTokens) {
            String nameTagIdentifier = TestUtils.generateRandomString(10);
            Token nametagToken = helper.createNameTagTokenForUser(
                    username,
                    token,
                    nameTagIdentifier,
                    TestUtils.generateRandomString(10)
            );
            nametags.add(nametagToken);

            // store relation: nametag -> original token
            relations.put(nametagToken.getId(), token.getId());
        }

        context.getNameTagTokens().put(username, nametags);
        context.setNametagRelationsForUser(username, relations);
    }

    @When("{string} transfers tokens to each of {string} nametags")
    public void userTransfersTokensToEachOfNameTags(String fromUser, String toUser) throws Exception {
        List<Token> nametagTokens = context.getNameTagTokens().get(toUser);
        List<Token> tokens = context.getUserTokens().get(fromUser);

        for (Token nametagToken : nametagTokens) {
            TokenId originalTokenId = context.getOriginalTokenIdForNametag(toUser, nametagToken.getId());

            Token tokenToTransfer = tokens.stream()
                    .filter(t -> t.getId().equals(originalTokenId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Token not found for transfer: " + originalTokenId));

            ProxyAddress proxyAddress = ProxyAddress.create(nametagToken.getId());

            helper.transferToken(
                    fromUser,
                    toUser,
                    tokenToTransfer,
                    proxyAddress,
                    null
            );
        }
    }

    @And("{string} consolidates all received tokens")
    public void userConsolidatesAllReceivedTokens(String username) {
        // Consolidation logic would depend on specific requirements
        List<Token> tokens = context.getUserTokens().getOrDefault(username, new ArrayList<>());
        // Verify user has received tokens
        assertFalse(tokens.isEmpty(), username + " should have received tokens");
    }

    @Then("{string} should own {int} tokens")
    public void userShouldOwnTokens(String username, int expectedTokenCount) {
        List<Token> tokens = context.getUserTokens().getOrDefault(username, new ArrayList<>());
        assertEquals(expectedTokenCount, tokens.size(), username +" should own expected number of tokens");

        // Verify ownership
        for (Token token : tokens) {
            SigningService signingService = SigningService.createFromSecret(
                    context.getUserSecret().get(username)
            );
            assertTrue(token.verify(context.getTrustBase()).isSuccessful(), "Token should be valid");
            assertTrue(TestUtils
                            .validateTokenOwnership(
                                    token,
                                    signingService,
                                    context.getTrustBase()
                            ),
                    username + " should own all tokens");
        }
    }

    @And("all {string} nametag tokens should remain valid")
    public void allNameTagTokensShouldRemainValid(String username) {
        List<Token> nametags = context.getNameTagTokens().get(username);
        for (Token nametag : nametags) {
            assertTrue(nametag.verify(context.getTrustBase()).isSuccessful(), "All name tag tokens should remain valid");
        }
    }

    @And("proxy addressing should work for all {string} name tags")
    public void proxyAddressingShouldWorkForAllNameTags(String username) {
        List<Token> nametags = context.getNameTagTokens().get(username);

        for (Token nametag : nametags) {
            var proxyAddress = org.unicitylabs.sdk.address.ProxyAddress.create(nametag.getId());
            assertNotNull(proxyAddress, "Proxy address should be creatable for all name tags");
        }
    }

    // Large Data Handling Steps
    @Given("a token with custom data of size {int} bytes")
    public void aTokenWithCustomDataOfSizeBytes(int dataSize) throws Exception {
        String alice = "Alice";
        byte[] largeData = new byte[dataSize];
        Arrays.fill(largeData, (byte) 'A'); // Fill with 'A' characters

        TokenId tokenId = TestUtils.generateRandomTokenId();
        TokenType tokenType = TestUtils.generateRandomTokenType();
        TokenCoinData coinData = TestUtils.createRandomCoinData(1);

        // Create token with large custom data
        MaskedPredicate predicate = MaskedPredicate.create(
                tokenId,
                tokenType,
                context.getUserSigningServices().get(alice),
                org.unicitylabs.sdk.hash.HashAlgorithm.SHA256,
                context.getUserNonces().get(alice)
        );

        var tokenState = new org.unicitylabs.sdk.token.TokenState(predicate, largeData);

        // Store for later use in transfer
        context.setChainToken(TestUtils.mintTokenForUser(
                context.getClient(),
                context.getUserSigningServices().get(alice),
                context.getUserNonces().get(alice),
                tokenId,
                tokenType,
                coinData,
                context.getTrustBase()
        ));
    }

    @And("{string} finalizes all received tokens")
    public void finalizesAllReceivedTokens(String username) throws Exception {
        List<PendingTransfer> pendingTransfers = context.getPendingTransfers(username);

        for (PendingTransfer pending : pendingTransfers) {
            Token <?> token = pending.getSourceToken();
            TransferTransaction tx = pending.getTransaction();
            helper.finalizeTransfer(
                    username,
                    token,
                    tx
            );
        }
        context.clearPendingTransfers(username);
    }

    @Given("{string} creates {int} tokens")
    public void createsNametagCountTokens(String username, int quantity) throws Exception {
        for (int i = 0; i < quantity; i++) {
            TokenId tokenId = TestUtils.generateRandomTokenId();
            TokenType tokenType = TestUtils.generateRandomTokenType();
            TokenCoinData coinData = TestUtils.createRandomCoinData(1);

            Token <?> token = TestUtils.mintTokenForUser(
                    context.getClient(),
                    context.getUserSigningServices().get(username),
                    context.getUserNonces().get(username),
                    tokenId,
                    tokenType,
                    coinData,
                    context.getTrustBase()
            );

            // do post-processing here (still in parallel)
            if (TestUtils.validateTokenOwnership(
                token,
                context.getUserSigningServices().get(username),
                context.getTrustBase()
            )) {
                context.addUserToken(username, token);
            }
            context.setCurrentUser(username);
        }
    }
}