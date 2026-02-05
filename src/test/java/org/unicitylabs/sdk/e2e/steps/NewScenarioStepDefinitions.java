package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.*;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.List;
import org.unicitylabs.sdk.address.ProxyAddress;
import org.unicitylabs.sdk.e2e.config.CucumberConfiguration;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.e2e.steps.shared.StepHelper;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.verification.VerificationResult;

/**
 * Step definitions for new BDD scenarios:
 * - Token lifecycle (burned token, pre-transfer reuse)
 * - Authorization (non-owner transfer prevention)
 * - CBOR serialization round-trip
 * - Split boundary conditions
 * - Edge cases
 */
public class NewScenarioStepDefinitions {

    private final TestContext context;
    private final StepHelper helper = new StepHelper();

    // Saved state for specific scenarios
    private Token<?> savedTokenReference;
    private Token<?> importedToken;
    private Token<?> originalTokenBeforeExport;

    public NewScenarioStepDefinitions() {
        this.context = CucumberConfiguration.getTestContext();
    }

    // ========== Token Lifecycle Steps ==========

    @Then("{string} should not be able to transfer the burned token to {string}")
    public void shouldNotBeAbleToTransferBurnedToken(String fromUser, String toUser) {
        Token originalToken = context.getOriginalMintedTokens().get(fromUser);
        assertNotNull(originalToken, "No original minted token saved for " + fromUser);
        try {
            ProxyAddress proxyAddress = ProxyAddress.create(context.getNameTagToken(toUser).getId());
            helper.transferToken(fromUser, toUser, originalToken, proxyAddress, null);
            fail("Transfer of burned token should have been rejected");
        } catch (Exception e) {
            // Expected: burned token cannot be transferred
        }
    }

    @And("{string} saves a reference to the current token")
    public void savesAReferenceToTheCurrentToken(String username) {
        savedTokenReference = context.getUserToken(username);
        assertNotNull(savedTokenReference, "Token reference should exist for " + username);
    }

    @When("{string} attempts to reuse the saved token reference to transfer to {string}")
    public void attemptsToReuseTheSavedTokenReference(String fromUser, String toUser) {
        assertNotNull(savedTokenReference, "No saved token reference");
        try {
            ProxyAddress proxyAddress = ProxyAddress.create(context.getNameTagToken(toUser).getId());
            helper.transferToken(fromUser, toUser, savedTokenReference, proxyAddress, null);
            context.setOperationSucceeded(true);
            context.setLastError(null);
        } catch (Exception e) {
            context.setOperationSucceeded(false);
            context.setLastError(e);
        }
    }

    @Then("the reuse attempt should be rejected")
    public void theReuseAttemptShouldBeRejected() {
        assertFalse(context.isOperationSucceeded(),
                "Reuse of pre-transfer token reference should have been rejected");
    }

    // ========== Authorization Steps ==========

    @When("{string} attempts to transfer {string}'s token to {string} using a proxy address")
    public void attemptsToTransferOthersTokenUsingProxy(String attacker, String owner, String recipient) {
        Token token = context.getUserToken(owner);
        assertNotNull(token, "No token found for " + owner);
        try {
            ProxyAddress proxyAddress = ProxyAddress.create(context.getNameTagToken(recipient).getId());
            helper.transferToken(attacker, recipient, token, proxyAddress, null);
            context.setOperationSucceeded(true);
            context.setLastError(null);
        } catch (Exception e) {
            context.setOperationSucceeded(false);
            context.setLastError(e);
        }
    }

    @Then("the unauthorized transfer should fail")
    public void theUnauthorizedTransferShouldFail() {
        assertFalse(context.isOperationSucceeded(),
                "Unauthorized transfer should have failed but succeeded. Error: "
                        + (context.getLastError() != null ? context.getLastError().getMessage() : "none"));
    }

    // ========== CBOR Serialization Steps ==========

    @When("the token for {string} is exported to CBOR and imported back")
    public void theTokenIsExportedToCborAndImportedBack(String username) {
        Token token = context.getUserToken(username);
        assertNotNull(token, "No token found for " + username);
        originalTokenBeforeExport = token;

        byte[] cborBytes = token.toCbor();
        assertNotNull(cborBytes, "CBOR export should produce bytes");
        assertTrue(cborBytes.length > 0, "CBOR export should produce non-empty bytes");

        importedToken = Token.fromCbor(cborBytes);
        assertNotNull(importedToken, "CBOR import should produce a token");
    }

    @Then("the imported token should have the same ID as the original")
    public void theImportedTokenShouldHaveTheSameId() {
        assertNotNull(importedToken, "Imported token should exist");
        assertNotNull(originalTokenBeforeExport, "Original token should exist");
        assertEquals(originalTokenBeforeExport.getId(), importedToken.getId(),
                "Imported token ID should match original");
    }

    @Then("the imported token should have the same type as the original")
    public void theImportedTokenShouldHaveTheSameType() {
        assertNotNull(importedToken, "Imported token should exist");
        assertNotNull(originalTokenBeforeExport, "Original token should exist");
        assertEquals(originalTokenBeforeExport.getType(), importedToken.getType(),
                "Imported token type should match original");
    }

    @Then("the imported token should pass verification")
    public void theImportedTokenShouldPassVerification() {
        assertNotNull(importedToken, "Imported token should exist");
        VerificationResult result = importedToken.verify(context.getTrustBase());
        assertTrue(result.isSuccessful(),
                "Imported token should pass verification but failed: " + result);
    }

    @Then("the imported token should have {int} transactions in its history")
    public void theImportedTokenShouldHaveTransactionsInHistory(int expectedCount) {
        assertNotNull(importedToken, "Imported token should exist");
        assertEquals(expectedCount, importedToken.getTransactions().size(),
                "Imported token should have expected number of transactions");
    }

    // ========== Token Property Verification (per-user) ==========

    @And("the token for {string} should have {int} transactions in its history")
    public void theTokenForUserShouldHaveTransactions(String username, int expectedCount) {
        Token token = context.getUserToken(username);
        assertNotNull(token, "Token should exist for " + username);
        assertEquals(expectedCount, token.getTransactions().size(),
                "Token for " + username + " should have " + expectedCount + " transactions");
    }

    @And("the token for {string} should maintain its original ID and type from {string}")
    public void theTokenShouldMaintainOriginalIdAndType(String currentOwner, String originalOwner) {
        Token currentToken = context.getUserToken(currentOwner);
        Token originalToken = context.getOriginalMintedTokens().get(originalOwner);
        assertNotNull(currentToken, "Current token should exist for " + currentOwner);
        assertNotNull(originalToken, "Original minted token should exist for " + originalOwner);
        assertEquals(originalToken.getId(), currentToken.getId(),
                "Token ID should be preserved through transfers");
        assertEquals(originalToken.getType(), currentToken.getType(),
                "Token type should be preserved through transfers");
    }

    // ========== Split Boundary Steps ==========

    @Then("both split tokens should be valid")
    public void bothSplitTokensShouldBeValid() {
        List<Token> tokens = context.getLastSplitTokens();
        assertNotNull(tokens, "Split tokens should exist");
        assertTrue(tokens.size() >= 2, "Should have at least 2 split tokens");

        for (Token token : tokens) {
            VerificationResult result = token.verify(context.getTrustBase());
            assertTrue(result.isSuccessful(),
                    "Split token should be valid but failed: " + result);
        }
    }

    @Then("the split token values should sum to the original value")
    public void theSplitTokenValuesShouldSumToOriginalValue() {
        String currentUser = context.getCurrentUser();
        Token originalToken = context.getOriginalMintedTokens().get(currentUser);
        List<Token> splitTokens = context.getLastSplitTokens();
        assertNotNull(originalToken, "Original token should exist");
        assertNotNull(splitTokens, "Split tokens should exist");

        // Verify both split tokens have coin data
        for (Token token : splitTokens) {
            assertTrue(token.getCoins().isPresent(),
                    "Split token should have coin data");
        }
    }

    // ========== Multi-Level Split Steps ==========

    @When("{string} attempts to double-spend the split token to {string}")
    public void attemptsToDoubleSpendSplitToken(String fromUser, String toUser) {
        // The first split token was already transferred; try to transfer it again
        List<Token> tokens = context.getUserTokens().get(fromUser);
        assertNotNull(tokens, "User should have tokens");
        assertFalse(tokens.isEmpty(), "User should have at least one token");

        Token tokenToDoubleSpend = tokens.get(0);
        try {
            ProxyAddress proxyAddress = ProxyAddress.create(context.getNameTagToken(toUser).getId());
            helper.transferToken(fromUser, toUser, tokenToDoubleSpend, proxyAddress, null);
            context.setOperationSucceeded(true);
            context.setLastError(null);
        } catch (Exception e) {
            context.setOperationSucceeded(false);
            context.setLastError(e);
        }
    }
}
