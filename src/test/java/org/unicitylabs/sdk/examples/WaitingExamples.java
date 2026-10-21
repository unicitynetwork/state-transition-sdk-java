package org.unicitylabs.sdk.examples;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import okhttp3.OkHttpClient;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.BatchRequestOptions;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofBatch;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.InclusionProofWaitException;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;
import org.unicitylabs.sdk.api.RequestOptions;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.SubmitOutcome;
import org.unicitylabs.sdk.api.WaitOptions;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.InclusionProofUtils;

/**
 * The waiting snippets of docs/api-reference.md. They are compiled with the tests, not run: their
 * purpose is to keep the documented calls in step with the API.
 */
final class WaitingExamples {

  private WaitingExamples() {
  }

  static InclusionProof standardWait(StateTransitionClient client, RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier, Transaction transaction) throws Exception {
    // long polling, 30 s in total, the first request goes out at once
    return InclusionProofUtils
        .waitInclusionProof(client, trustBase, predicateVerifier, transaction)
        .get();
  }

  static InclusionProof tunedWait(StateTransitionClient client, RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier, Transaction transaction) throws Exception {
    var options = WaitOptions.defaults().timeout(Duration.ofSeconds(60));
    return InclusionProofUtils
        .waitInclusionProof(client, trustBase, predicateVerifier, transaction, options)
        .get();
  }

  static CompletableFuture<InclusionProof> cancellable(StateTransitionClient client,
      RootTrustBase trustBase, PredicateVerifierService predicateVerifier, Transaction transaction) {
    CompletableFuture<InclusionProof> wait =
        InclusionProofUtils.waitInclusionProof(client, trustBase, predicateVerifier, transaction);
    // cancelling the future cancels the HTTP call in flight and the pending pause
    wait.cancel(true);
    return wait;
  }

  static boolean resubmittable(Throwable failure) {
    return failure instanceof InclusionProofWaitException
        && ((InclusionProofWaitException) failure).isResubmittable();
  }

  static CompletableFuture<InclusionProof> submitAndWait(StateTransitionClient client,
      Transaction transaction, CertificationData data, RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier) {
    // the first submission and at most two resubmissions; the proof must certify this transaction
    return client.submitAndWait(transaction, data, 2, trustBase, predicateVerifier,
        WaitOptions.defaults());
  }

  static CompletableFuture<SubmitOutcome> resubmit(StateTransitionClient client,
      CertificationData data) {
    return client.resubmitCertificationRequest(data, RequestOptions.none());
  }

  static CompletableFuture<InclusionProofResponse> lookupWithHint(StateTransitionClient client,
      StateId stateId) {
    return client.getInclusionProof(stateId, RequestOptions.none().withStatusHint(true));
  }

  static CompletableFuture<InclusionProofBatch> latestBatch(StateTransitionClient client,
      List<StateId> stateIds) {
    // hold the request while every missing state is pending; retried once without the wait by a
    // server that does not know the parameter
    return client.getInclusionProofs(stateIds, BatchRequestOptions.latest().withWaitMillis(5_000));
  }

  static JsonRpcAggregatorClient sharedHttpClient(String url, String apiKey, OkHttpClient http) {
    // shares the connection pool and dispatcher of the application's client
    return new JsonRpcAggregatorClient(url, apiKey, false, http);
  }
}
