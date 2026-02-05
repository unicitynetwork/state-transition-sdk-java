package org.unicitylabs.sdk.e2e.steps.shared;

import org.unicitylabs.sdk.address.Address;
import org.unicitylabs.sdk.address.DirectAddress;
import org.unicitylabs.sdk.address.ProxyAddress;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.e2e.config.CucumberConfiguration;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.hash.DataHash;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.SerializablePredicate;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate;
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicateReference;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenId;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.transaction.*;
import org.unicitylabs.sdk.utils.TestUtils;
import org.unicitylabs.sdk.utils.helpers.AggregatorRequestHelper;
import org.unicitylabs.sdk.utils.helpers.CommitmentResult;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.unicitylabs.sdk.util.InclusionProofUtils.waitInclusionProof;
import static org.unicitylabs.sdk.utils.TestUtils.randomBytes;


public class StepHelper {

    private final TestContext context;

    public StepHelper() {  // ✅ Public zero-argument constructor
        this.context = CucumberConfiguration.getTestContext();
    }

    public Token createNameTagTokenForUser(String username, Token token, String nameTagIdentifier, String nametagData) throws Exception {
        byte[] nametagNonce = TestUtils.generateRandomBytes(32);

       TokenType nametagTokenType = TestUtils.generateRandomTokenType();

        MaskedPredicate nametagPredicate = MaskedPredicate.create(
                TokenId.fromNameTag(nameTagIdentifier),//person name actually should go in here from contacts (unique thing from contacts)
                nametagTokenType,
                SigningService.createFromMaskedSecret(context.getUserSecret().get(username), nametagNonce),
                HashAlgorithm.SHA256,
                nametagNonce
        );

        DirectAddress nametagAddress = nametagPredicate.getReference().toAddress();

        DirectAddress userAddress = UnmaskedPredicateReference.create(
                token.getType(),
                SigningService.createFromSecret(context.getUserSecret().get(username)),
                HashAlgorithm.SHA256
        ).toAddress();

        var nametagMintCommitment = MintCommitment.create(
                new MintTransaction.NametagData(
                        nameTagIdentifier,
                        nametagTokenType,
                        nametagAddress,
                        TestUtils.generateRandomBytes(32),
                        userAddress
                )
        );

        SubmitCommitmentResponse response = context.getClient().submitCommitment(nametagMintCommitment).get();
        if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
            throw new Exception("Failed to submit nametag mint commitment: " + response.getStatus());
        }

        InclusionProof inclusionProof = waitInclusionProof(
                context.getClient(),
                context.getTrustBase(),
                nametagMintCommitment
        ).get();
        MintTransaction<?> nametagGenesis = nametagMintCommitment.toTransaction(inclusionProof);

        return Token.create(
                context.getTrustBase(),
                new TokenState(nametagPredicate, null),
                nametagGenesis
        );
    }
    public void transferToken(String fromUser, String toUser, Token<?> token, Address toAddress, String customData) throws Exception {
        SigningService fromSigningService = getSigningServiceForToken(fromUser, token);

        DataHash dataHash = null;
        byte[] stateData = null;
        if (customData != null && !customData.isEmpty()) {
            stateData = customData.getBytes(StandardCharsets.UTF_8);
            dataHash = TestUtils.hashData(stateData);
        }

        TransferCommitment transferCommitment = TransferCommitment.create(
                token,
                toAddress,
                randomBytes(32),
                dataHash,
                null,
                fromSigningService
        );

        SubmitCommitmentResponse response = context.getClient().submitCommitment(transferCommitment).get();
        if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
            throw new Exception("Failed to submit transfer commitment: " + response.getStatus());
        }

        InclusionProof inclusionProof = waitInclusionProof(context.getClient(), context.getTrustBase(), transferCommitment).get();
        TransferTransaction transferTransaction = transferCommitment.toTransaction(inclusionProof);

        context.savePendingTransfer(toUser, token, transferTransaction);
    }

    public void finalizeTransfer(String username, Token<?> token, TransferTransaction tx) throws Exception {
        byte[] secret = context.getUserSecret().get(username);

        Token<?> currentNameTagToken = context.getNameTagToken(username);
        List<Token> nametagTokens = context.getNameTagTokens().get(username);
        if (nametagTokens != null && !nametagTokens.isEmpty()) {
            for (Token<?> t : nametagTokens) {
                String actualNametagAddress = tx.getData().getRecipient().getAddress();
                String expectedProxyAddress = ProxyAddress.create(t.getId()).getAddress();
                if (actualNametagAddress.equalsIgnoreCase(expectedProxyAddress)) {
                    currentNameTagToken = t;
                    break;
                }
            }
        }

        List<Token<?>> additionalTokens = new ArrayList<>();
        if (currentNameTagToken != null) {
            additionalTokens.add(currentNameTagToken);
        }



        PredicateType predicateType = detectPredicateType(username, token, tx);
        Predicate recipientPredicate = createRecipientPredicate(
                username,
                token,
                tx,
                predicateType
        );

        TokenState recipientState = new TokenState(recipientPredicate, null);
        Token finalizedToken = context.getClient().finalizeTransaction(
                context.getTrustBase(),
                token,
                recipientState,
                tx,
                additionalTokens
        );

        context.getUserSigningServices().put(username, getSigningServiceForToken(username, finalizedToken));
        context.addUserToken(username, finalizedToken);
    }

    public boolean submitSingleCommitment() {
        try {
            byte[] randomSecret = TestUtils.generateRandomBytes(32);
            byte[] stateBytes = TestUtils.generateRandomBytes(32);
            byte[] txData = TestUtils.generateRandomBytes(32);

            DataHash stateHash = TestUtils.hashData(stateBytes);
            DataHash txDataHash = TestUtils.hashData(txData);
            SigningService signingService = SigningService.createFromSecret(randomSecret);
            var requestId = TestUtils.createRequestId(signingService, stateHash);
            var authenticator = TestUtils.createAuthenticator(signingService, txDataHash, stateHash);

            SubmitCommitmentResponse response = context.getAggregatorClient()
                    .submitCommitment(requestId, txDataHash, authenticator).get();
            return response.getStatus() == SubmitCommitmentStatus.SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    public void verifyAllInclusionProofsInParallel(int timeoutSeconds)
            throws InterruptedException {
        List<CommitmentResult> results = collectCommitmentResults();
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        CountDownLatch latch = new CountDownLatch(results.size());

        long startAll = System.nanoTime();
        long globalTimeout = startAll + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        for (CommitmentResult result : results) {
            executor.submit(() -> {
                long inclStart = System.nanoTime();
                boolean verified = false;
                String errorMessage = "Global timeout reached";

                try {
                    while (System.nanoTime() < globalTimeout && !verified) {
                        try {
                            InclusionProof proof = context.getAggregatorClient()
                                    .getInclusionProof(result.getRequestId())
                                    .get(calculateRemainingTimeout(globalTimeout), TimeUnit.MILLISECONDS).getInclusionProof();

                            if (proof != null && proof.verify(result.getRequestId(), context.getTrustBase())
                                    == InclusionProofVerificationStatus.OK) {
                                result.markVerified(inclStart, System.nanoTime());
                                verified = true;
                            } else {
                                InclusionProofVerificationStatus status = proof.verify(result.getRequestId(), context.getTrustBase());
                                errorMessage = status.toString();
                                Thread.sleep(1000); // Небольшая пауза перед повторной попыткой
                            }
                        } catch (TimeoutException e) {
                            errorMessage = "Individual operation timeout: " + e.getMessage();
                        } catch (ExecutionException e) {
                            errorMessage = "Execution error: " + e.getMessage();
                            Thread.sleep(1000); // Пауза перед повторной попыткой
                        }
                    }

                    if (!verified) {
                        result.markFailedVerification(inclStart, System.nanoTime(), errorMessage);
                    }

                } catch (Exception e) {
                    result.markFailedVerification(inclStart, System.nanoTime(),
                            "Unexpected error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all tasks to complete or timeout
        boolean finished = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        executor.shutdownNow();

        long endAll = System.nanoTime();
        System.out.println("All inclusion proofs completed in: " + ((endAll - startAll) / 1_000_000) + " ms");

        if (!finished) {
            System.err.println("Timeout reached before all inclusion proofs were verified");
        }
    }

    private long calculateRemainingTimeout(long globalTimeoutNanos) {
        long remaining = globalTimeoutNanos - System.nanoTime();
        return TimeUnit.NANOSECONDS.toMillis(Math.max(remaining, 100)); // Минимум 100мс
    }

    public List<CommitmentResult> collectCommitmentResults() {
        return context.getCommitmentFutures().stream()
                .map(f -> {
                    try {
                        return f.get(); // wait for completion
                    } catch (Exception e) {
                        e.printStackTrace();
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // Modified method to extract shard/aggregator from userName or use shard URL mapping
    public String extractAggregatorFromUserName(String userName) {
        if (userName.contains("-Aggregator")) {
            return userName.substring(userName.indexOf("-Aggregator"));
        }
        if (userName.contains("-Shard")) {
            return userName.substring(userName.indexOf("-Shard"));
        }
        return "Unknown-Aggregator";
    }

    // New method to map shard URL to aggregator index
    public String mapShardUrlToAggregator(String shardUrl, List<String> aggregatorUrls) {
        for (int i = 0; i < aggregatorUrls.size(); i++) {
            if (shardUrl.equals(aggregatorUrls.get(i))) {
                return "-Aggregator" + i;
            }
        }
        return "Unknown-Aggregator";
    }

    // Updated helper method for your existing CommitmentResult class
    public void verifyAllInclusionProofsInParallelForMultipleAggregators(int timeoutSeconds, List<AggregatorClient> aggregatorClients) throws Exception {
        List<CommitmentResult> results = collectCommitmentResults();

        // Group results by aggregator
        Map<String, List<CommitmentResult>> resultsByAggregator = results.stream()
                .collect(Collectors.groupingBy(r -> extractAggregatorFromUserName(r.getUserName())));

        ExecutorService executor = Executors.newFixedThreadPool(aggregatorClients.size());
        List<CompletableFuture<Void>> verificationFutures = new ArrayList<>();

        for (int i = 0; i < aggregatorClients.size(); i++) {
            AggregatorClient aggregatorClient = aggregatorClients.get(i);
            String aggregatorId = "Aggregator" + i;
            List<CommitmentResult> aggregatorResults = resultsByAggregator.getOrDefault("-" + aggregatorId, new ArrayList<>());

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    verifyInclusionProofsForAggregator(aggregatorClient, aggregatorResults, timeoutSeconds);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to verify inclusion proofs for " + aggregatorId, e);
                }
            }, executor);

            verificationFutures.add(future);
        }

        try {
            CompletableFuture.allOf(verificationFutures.toArray(new CompletableFuture[0]))
                    .get(timeoutSeconds + 10, TimeUnit.SECONDS); // Add buffer time for processing
        } finally {
            executor.shutdown();
        }
    }

    public void verifyAllInclusionProofsInParallelForShardAggregators(
            int timeoutSeconds,
            AggregatorRequestHelper shardHelper) throws Exception {

        List<CommitmentResult> results = collectCommitmentResults();

        // Group results by shard ID instead of Aggregator name
        Map<Integer, List<CommitmentResult>> resultsByShard = results.stream()
                .collect(Collectors.groupingBy(r -> shardHelper.getShardForRequest(r.getRequestId())));

        ExecutorService executor = Executors.newFixedThreadPool(resultsByShard.size());
        List<CompletableFuture<Void>> verificationFutures = new ArrayList<>();

        for (Map.Entry<Integer, List<CommitmentResult>> entry : resultsByShard.entrySet()) {
            int shardId = entry.getKey();
            List<CommitmentResult> shardResults = entry.getValue();
            AggregatorClient aggregatorClient = shardHelper.getClientForShard(shardId);
            String shardUrl = shardHelper.getShardUrl(shardId);

            if (aggregatorClient == null) {
                System.out.printf("⚠️ No aggregator found for shard %d, skipping %d results%n", shardId, shardResults.size());
                continue;
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    System.out.printf("🔍 Verifying inclusion proofs for shard %d (%s)%n", shardId, shardUrl);
                    verifyInclusionProofsForAggregator(aggregatorClient, shardResults, timeoutSeconds);

                    // Update shard-level stats
                    long verified = shardResults.stream().filter(CommitmentResult::isVerified).count();
                    shardHelper.getStats(shardId).incrementCommitments();
                    shardHelper.getStats(shardId).incrementSuccessBy((int) verified);
                    shardHelper.getStats(shardId).incrementFailuresBy(shardResults.size() - (int) verified);

                    System.out.printf("✅ Shard %d verification complete: %d/%d verified%n",
                            shardId, verified, shardResults.size());
                } catch (Exception e) {
                    System.err.printf("❌ Error verifying shard %d: %s%n", shardId, e.getMessage());
                    shardHelper.getStats(shardId).incrementFailures();
                }
            }, executor);

            verificationFutures.add(future);
        }

        // Wait for all shard verifications to complete
        CompletableFuture.allOf(verificationFutures.toArray(new CompletableFuture[0]))
                .get(timeoutSeconds + 10, TimeUnit.SECONDS);

        executor.shutdown();
        shardHelper.printShardStats();
    }

    private void verifyInclusionProofsForAggregator(AggregatorClient aggregatorClient,
                                                    List<CommitmentResult> results,
                                                    int timeoutSeconds) throws Exception {
        long globalStartTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        for (CommitmentResult result : results) {
            long inclusionStartTime = System.nanoTime();

            if (!result.isSuccess()) {
                long inclusionEndTime = System.nanoTime();
                result.markFailedVerification(inclusionStartTime, inclusionEndTime, "Commitment submission failed");
                continue;
            }

            boolean verified = false;
            String statusMessage = "Timeout waiting for inclusion proof";

            // Poll for inclusion proof with timeout
            while (System.currentTimeMillis() - globalStartTime < timeoutMillis) {
                try {
                    // Check if inclusion proof is available
                     InclusionProof proofResponse = aggregatorClient
                            .getInclusionProof(result.getRequestId()).get(5, TimeUnit.SECONDS).getInclusionProof();
                    if (proofResponse != null && proofResponse.verify(result.getRequestId(), context.getTrustBase())
                            == InclusionProofVerificationStatus.OK) {
                        System.out.println("InclusionProofVerificationStatus.OK");
                        result.markVerified(inclusionStartTime, System.nanoTime());
                        verified = true;
                        break;
                    } else {
                        InclusionProofVerificationStatus status = proofResponse.verify(result.getRequestId(), context.getTrustBase());
                        System.out.println(status.toString());
                        statusMessage = status.toString();
                    }
                    Thread.sleep(1000);
                } catch (TimeoutException e) {
                    // Continue polling
                    statusMessage = "Timeout during proof retrieval";
                } catch (Exception e) {
                    statusMessage = "Error retrieving proof: " + e.getMessage();
                    break;
                }
            }

            long inclusionEndTime = System.nanoTime();

            // Use your existing methods to mark verification result
            if (verified) {
                result.markVerified(inclusionStartTime, inclusionEndTime);
            } else {
                result.markFailedVerification(inclusionStartTime, inclusionEndTime, statusMessage);
            }
        }
    }

    // Modified method to group results by actual shard/aggregator URL
    public void printDetailedResultsByAggregator(List<CommitmentResult> results,
                                                 List<AggregatorClient> aggregatorClients,
                                                 List<String> aggregatorUrls) {
        System.out.println("\n=== 📊 AGGREGATOR PERFORMANCE COMPARISON ===");
        System.out.println("\n=== Detailed Results by Shard ===");

        // Group by what's actually in the username (could be -Shard or -Aggregator)
        Map<String, List<CommitmentResult>> resultsByIdentifier = results.stream()
                .collect(Collectors.groupingBy(r -> extractAggregatorFromUserName(r.getUserName())));

        // Create a map from shard URLs to aggregator indices
        Map<String, Integer> urlToIndex = new HashMap<>();
        for (int i = 0; i < aggregatorUrls.size(); i++) {
            urlToIndex.put(aggregatorUrls.get(i), i);
        }

        // Display results for each configured shard/aggregator
        for (int i = 0; i < aggregatorUrls.size(); i++) {
            String aggregatorUrl = aggregatorUrls.get(i);

            // Try to find results for this aggregator by index
            String aggregatorId = "-Aggregator" + i;
            String shardId = "-Shard" + i;

            // Collect results from both possible identifiers
            List<CommitmentResult> aggregatorResults = new ArrayList<>();
            aggregatorResults.addAll(resultsByIdentifier.getOrDefault(aggregatorId, new ArrayList<>()));
            aggregatorResults.addAll(resultsByIdentifier.getOrDefault(shardId, new ArrayList<>()));

            // Also check for any shard identifiers that might map to this URL
            // (in case shard numbering doesn't match aggregator numbering)
            for (Map.Entry<String, List<CommitmentResult>> entry : resultsByIdentifier.entrySet()) {
                String identifier = entry.getKey();
                // Skip if we already processed this identifier
                if (identifier.equals(aggregatorId) || identifier.equals(shardId)) {
                    continue;
                }
                // Check if any result in this group was sent to this URL
                // (You'd need to track the URL in CommitmentResult for this to work properly)
            }

            long verifiedCount = aggregatorResults.stream()
                    .filter(CommitmentResult::isVerified)
                    .count();

            double successRate = aggregatorResults.isEmpty() ? 0 :
                    (double) verifiedCount / aggregatorResults.size() * 100;

            OptionalDouble avgInclusionTime = aggregatorResults.stream()
                    .filter(CommitmentResult::isVerified)
                    .mapToDouble(CommitmentResult::getInclusionDurationMillis)
                    .average();

            System.out.println("\nShard/Aggregator " + i + " (" + aggregatorUrl + "):");
            System.out.println("  Total commitments: " + aggregatorResults.size());
            System.out.println("  Verified: " + verifiedCount + " / " + aggregatorResults.size());
            System.out.println("  Success rate: " + String.format("%.2f%%", successRate));

            if (avgInclusionTime.isPresent()) {
                System.out.println("  Average inclusion time: " + String.format("%.2f ms", avgInclusionTime.getAsDouble()));
            } else if (!aggregatorResults.isEmpty()) {
                System.out.println("  ⚠️ No verified commitments to calculate timing");
            }

            if (aggregatorResults.isEmpty()) {
                System.out.println("  ℹ️ No commitments found for this shard/aggregator");
            } else {
                List<CommitmentResult> failed = aggregatorResults.stream()
                        .filter(r -> !r.isVerified())
                        .collect(Collectors.toList());

                if (!failed.isEmpty()) {
                    System.out.println("  Failed verifications (" + failed.size() + "):");
                    failed.forEach(r -> System.out.println("    ❌ " + r.getRequestId() +
                            " - " + (r.getStatus() != null ? r.getStatus() : "Unknown error")));
                } else {
                    System.out.println("  ✅ All commitments verified successfully!");
                }
            }
        }

        // Show any unmatched results
        System.out.println("\n=== Unmatched Results ===");
        for (Map.Entry<String, List<CommitmentResult>> entry : resultsByIdentifier.entrySet()) {
            String identifier = entry.getKey();

            // Skip if it matches our expected patterns
            boolean matched = false;
            for (int i = 0; i < aggregatorUrls.size(); i++) {
                if (identifier.equals("-Aggregator" + i) || identifier.equals("-Shard" + i)) {
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                List<CommitmentResult> unmatchedResults = entry.getValue();
                long verifiedCount = unmatchedResults.stream()
                        .filter(CommitmentResult::isVerified)
                        .count();

                System.out.println(identifier + ":");
                System.out.println("  Total commitments: " + unmatchedResults.size());
                System.out.println("  Verified: " + verifiedCount + " / " + unmatchedResults.size());

                DoubleSummaryStatistics stats = unmatchedResults.stream()
                        .filter(CommitmentResult::isVerified)
                        .mapToDouble(CommitmentResult::getInclusionDurationMillis)
                        .summaryStatistics();

                if (stats.getCount() > 0) {
                    System.out.println(String.format("  Average inclusion time: %.2f ms", stats.getAverage()));
                }
            }
        }

        System.out.println("=====================================");
    }

    // Add this helper method to your test class or helper class
    public void printInclusionProofStatistics(List<CommitmentResult> results) {
        // Overall statistics
        DoubleSummaryStatistics overallStats = results.stream()
                .filter(CommitmentResult::isVerified)
                .mapToDouble(CommitmentResult::getInclusionDurationMillis)
                .summaryStatistics();

        System.out.println("\n=== 📊 INCLUSION PROOF TIMING STATISTICS ===");

        if (overallStats.getCount() > 0) {
            System.out.println("Overall Performance:");
            System.out.println(String.format("  Average: %.2f ms (%.2f seconds)",
                    overallStats.getAverage(), overallStats.getAverage() / 1000));
            System.out.println(String.format("  Minimum: %.2f ms", overallStats.getMin()));
            System.out.println(String.format("  Maximum: %.2f ms", overallStats.getMax()));
            System.out.println(String.format("  Total verified: %d", overallStats.getCount()));
        }

        // Per-aggregator statistics
        Map<String, List<CommitmentResult>> resultsByAggregator = results.stream()
                .collect(Collectors.groupingBy(r -> extractAggregatorFromUserName(r.getUserName())));

        System.out.println("\n=== Per-Aggregator Timing Statistics ===");
        for (Map.Entry<String, List<CommitmentResult>> entry : resultsByAggregator.entrySet()) {
            String aggregatorId = entry.getKey();
            List<CommitmentResult> aggregatorResults = entry.getValue();

            DoubleSummaryStatistics aggregatorStats = aggregatorResults.stream()
                    .filter(CommitmentResult::isVerified)
                    .mapToDouble(CommitmentResult::getInclusionDurationMillis)
                    .summaryStatistics();

            if (aggregatorStats.getCount() > 0) {
                System.out.println(aggregatorId + ":");
                System.out.println(String.format("  Average: %.2f ms", aggregatorStats.getAverage()));
                System.out.println(String.format("  Min: %.2f ms", aggregatorStats.getMin()));
                System.out.println(String.format("  Max: %.2f ms", aggregatorStats.getMax()));
                System.out.println(String.format("  Verified count: %d", aggregatorStats.getCount()));
            }
        }
        System.out.println("==========================================");
    }

    public void printPerformanceComparison(List<CommitmentResult> results, int aggregatorCount) {
        Map<String, List<CommitmentResult>> resultsByAggregator = results.stream()
                .collect(Collectors.groupingBy(r -> extractAggregatorFromUserName(r.getUserName())));

        System.out.println("=== 🏆 PERFORMANCE WINNER ANALYSIS ===");

        // Find best success rate
        double bestSuccessRate = 0;
        String bestSuccessAggregator = "";

        // Find fastest average inclusion time
        double fastestAvgTime = Double.MAX_VALUE;
        String fastestAggregator = "";

        for (int i = 0; i < aggregatorCount; i++) {
            String aggregatorId = "-Aggregator" + i;
            List<CommitmentResult> aggregatorResults = resultsByAggregator.getOrDefault(aggregatorId, new ArrayList<>());

            if (aggregatorResults.isEmpty()) continue;

            long verifiedCount = aggregatorResults.stream()
                    .filter(CommitmentResult::isVerified)
                    .count();

            double successRate = (double) verifiedCount / aggregatorResults.size() * 100;

            if (successRate > bestSuccessRate) {
                bestSuccessRate = successRate;
                bestSuccessAggregator = "Aggregator" + i;
            }

            OptionalDouble avgInclusionTime = aggregatorResults.stream()
                    .filter(CommitmentResult::isVerified)
                    .mapToDouble(CommitmentResult::getInclusionDurationMillis)
                    .average();

            if (avgInclusionTime.isPresent() && avgInclusionTime.getAsDouble() < fastestAvgTime) {
                fastestAvgTime = avgInclusionTime.getAsDouble();
                fastestAggregator = "Aggregator" + i;
            }
        }

        System.out.println("🥇 Highest Success Rate: " + bestSuccessAggregator +
                " (" + String.format("%.2f%%", bestSuccessRate) + ")");

        if (fastestAvgTime != Double.MAX_VALUE) {
            System.out.println("⚡ Fastest Inclusion Time: " + fastestAggregator +
                    " (" + String.format("%.2f ms", fastestAvgTime) + ")");
        }

        System.out.println("=====================================\n");
    }

    // MaskedPredicate uses SHA256(secret + nonce) as private key.
    // UnmaskedPredicate uses SHA256(secret). Using the wrong one causes
    // signature verification to fail silently.
    public SigningService getSigningServiceForToken(String username, Token<?> token) {
        byte[] secret = context.getUserSecret().get(username);
        SerializablePredicate predicate = token.getState().getPredicate();

        if (predicate instanceof MaskedPredicate) {
            MaskedPredicate maskedPredicate = (MaskedPredicate) predicate;
            return SigningService.createFromMaskedSecret(secret, maskedPredicate.getNonce());
        } else {
            return SigningService.createFromSecret(secret);
        }
    }

    public boolean isProxyTransfer(TransferTransaction tx) {
        return tx.getData().getRecipient() instanceof ProxyAddress;
    }

    public Predicate createRecipientPredicate(String username, Token<?> sourceToken, TransferTransaction tx, PredicateType type) {
        byte[] secret = context.getUserSecret().get(username);
        byte[] salt = tx.getData().getSalt();

        switch (type) {
            case UNMASKED:
                return UnmaskedPredicate.create(
                        sourceToken.getId(),
                        sourceToken.getType(),
                        SigningService.createFromSecret(secret),
                        HashAlgorithm.SHA256,
                        salt
                );
            case MASKED:
                MaskedPredicate srcMasked = (MaskedPredicate) sourceToken.getState().getPredicate();
                return MaskedPredicate.create(
                        sourceToken.getId(),
                        sourceToken.getType(),
                        SigningService.createFromMaskedSecret(secret, srcMasked.getNonce()),
                        HashAlgorithm.SHA256,
                        srcMasked.getNonce()
                );
            case NAMETAG_AWARE:
            default:
                if (isProxyTransfer(tx)) {
                    return UnmaskedPredicate.create(
                            sourceToken.getId(),
                            sourceToken.getType(),
                            SigningService.createFromSecret(secret),
                            HashAlgorithm.SHA256,
                            salt
                    );
                } else {
                    MaskedPredicate src = (MaskedPredicate) sourceToken.getState().getPredicate();
                    return MaskedPredicate.create(
                            sourceToken.getId(),
                            sourceToken.getType(),
                            SigningService.createFromMaskedSecret(secret, src.getNonce()),
                            HashAlgorithm.SHA256,
                            src.getNonce()
                    );
                }
        }
    }

    // Business rule: proxy (nametag) transfers always resolve to UnmaskedPredicate
    // on finalization. Direct transfers match predicate type to the actual recipient
    // address used in the transaction.
    private PredicateType detectPredicateType(String username, Token<?> token, TransferTransaction tx) {
        if (tx.getData().getRecipient() instanceof ProxyAddress) {
            // Transfer goes through a name-tag (proxy) system
            return PredicateType.NAMETAG_AWARE;
        }

        // For direct address transfers, check if the recipient address matches an
        // unmasked predicate. This correctly handles the case where the source token
        // uses a masked predicate but the transfer was done using an unmasked predicate.
        byte[] secret = context.getUserSecret().get(username);
        SigningService unmaskedSigning = SigningService.createFromSecret(secret);
        DirectAddress unmaskedAddress = UnmaskedPredicateReference.create(
                token.getType(),
                unmaskedSigning,
                HashAlgorithm.SHA256
        ).toAddress();

        if (unmaskedAddress.getAddress().equalsIgnoreCase(tx.getData().getRecipient().getAddress())) {
            return PredicateType.UNMASKED;
        }

        Predicate predicate = (Predicate) token.getState().getPredicate();

        if (predicate instanceof MaskedPredicate) {
            // Previous state was masked — continuing the masked ownership chain
            return PredicateType.MASKED;
        }

        // Default fallback: direct unmasked ownership
        return PredicateType.UNMASKED;
    }
}