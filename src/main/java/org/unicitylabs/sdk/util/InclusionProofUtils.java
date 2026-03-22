package org.unicitylabs.sdk.util;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationRule;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationStatus;
import org.unicitylabs.sdk.util.verification.VerificationException;
import org.unicitylabs.sdk.util.verification.VerificationResult;

/**
 * Utility class for working with inclusion proofs.
 */
public class InclusionProofUtils {

  private static final Logger logger = LoggerFactory.getLogger(InclusionProofUtils.class);
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(
      30);  // 30 seconds should be enough for direct leader
  private static final Duration DEFAULT_INTERVAL = Duration.ofMillis(1000);

  private InclusionProofUtils() {
  }

  /**
   * Wait for an inclusion proof to be available and verified.
   *
   * @param client            State transition client
   * @param trustBase         Root trust base
   * @param predicateVerifier Predicate verifier service
   * @param transaction       Transaction to wait Inclusion proof for
   * @return Completable future with inclusion proof
   */
  public static CompletableFuture<InclusionProof> waitInclusionProof(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      Transaction transaction
  ) {
    return waitInclusionProof(client, trustBase, predicateVerifier, transaction, DEFAULT_TIMEOUT,
        DEFAULT_INTERVAL);
  }

  /**
   * Wait for an inclusion proof to be available and verified with custom timeout.
   *
   * @param client            State transition client
   * @param trustBase         Root trust base
   * @param predicateVerifier Predicate verifier service
   * @param transaction       Transaction to wait Inclusion proof for
   * @param timeout           Maximum duration to wait for the inclusion proof
   * @param interval          Interval between checks for the inclusion proof
   * @return Completable future with inclusion proof
   */
  public static CompletableFuture<InclusionProof> waitInclusionProof(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      Transaction transaction,
      Duration timeout,
      Duration interval
  ) {

    CompletableFuture<InclusionProof> future = new CompletableFuture<>();

    long startTime = System.currentTimeMillis();
    long timeoutMillis = timeout.toMillis();

    checkInclusionProof(client, trustBase, predicateVerifier, transaction, future, startTime,
        timeoutMillis,
        interval.toMillis());

    return future;
  }

  private static void checkInclusionProof(
      StateTransitionClient client,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      Transaction transaction,
      CompletableFuture<InclusionProof> future,
      long startTime,
      long timeoutMillis,
      long intervalMillis) {
    if (System.currentTimeMillis() - startTime > timeoutMillis) {
      future.completeExceptionally(new TimeoutException("Timeout waiting for inclusion proof"));
    }

    StateId stateId = StateId.fromTransaction(transaction);
    client.getInclusionProof(stateId).thenAccept(response -> {
      VerificationResult<InclusionProofVerificationStatus> result = InclusionProofVerificationRule.verify(
          trustBase, predicateVerifier, response.getInclusionProof(), transaction);
      switch (result.getStatus()) {
        case OK:
          future.complete(response.getInclusionProof());
          break;
        case PATH_NOT_INCLUDED:
          CompletableFuture.delayedExecutor(intervalMillis, TimeUnit.MILLISECONDS)
              .execute(() -> checkInclusionProof(client, trustBase, predicateVerifier, transaction,
                  future, startTime,
                  timeoutMillis,
                  intervalMillis));
          break;
        default:
          future.completeExceptionally(
              new VerificationException("Inclusion proof verification failed", result));
      }
    }).exceptionally(e -> {
      future.completeExceptionally(e);
      return null;
    });
  }
}