package org.unicitylabs.sdk;

import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.BatchAggregatorClient;
import org.unicitylabs.sdk.api.BatchProofException;
import org.unicitylabs.sdk.api.BatchRequestOptions;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationRejectedException;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofBatch;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.InclusionProofWaitException;
import org.unicitylabs.sdk.api.NonInclusionAggregatorClient;
import org.unicitylabs.sdk.api.NonInclusionProofException;
import org.unicitylabs.sdk.api.NonInclusionProofResponse;
import org.unicitylabs.sdk.api.RequestOptions;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.StatusAwareAggregatorClient;
import org.unicitylabs.sdk.api.SubmitOutcome;
import org.unicitylabs.sdk.api.WaitOptions;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcDataException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcNetworkException;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.InclusionProofUtils;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Client for handling state transitions of tokens, including submitting commitments and finalizing transactions.
 */
public class StateTransitionClient {

  /** A duplicate on the state id index of the Mongo backed queue, as the server reports it. */
  private static final Pattern DUPLICATE_KEY = Pattern.compile("E11000|duplicate key",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern STATE_ID_INDEX = Pattern.compile("index:\\s*stateId_",
      Pattern.CASE_INSENSITIVE);

  /**
   * The aggregator client used for submitting commitments and retrieving inclusion proofs.
   */
  protected final AggregatorClient client;

  /**
   * Creates a new StateTransitionClient with the specified aggregator client.
   *
   * @param client The aggregator client to use for communication.
   */
  public StateTransitionClient(AggregatorClient client) {
    this.client = client;
  }

  /**
   * The aggregator client of this client.
   *
   * @return aggregator client
   */
  public AggregatorClient getAggregatorClient() {
    return this.client;
  }

  /**
   * Retrieves the inclusion proof for a given transaction.
   *
   * @param stateId The state ID of inclusion proof to retrieve.
   * @return inclusion proof response from the aggregator.
   */
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
    return this.client.getInclusionProof(stateId);
  }

  /**
   * Retrieves the inclusion proof answer with options: a wait, the status hint, a call timeout and
   * an operation. A client without {@link StatusAwareAggregatorClient} answers as without options.
   *
   * @param stateId The state ID of inclusion proof to retrieve.
   * @param options request options
   * @return inclusion proof response from the aggregator.
   */
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId,
      RequestOptions options) {
    if (this.client instanceof StatusAwareAggregatorClient) {
      return ((StatusAwareAggregatorClient) this.client).getInclusionProof(stateId, options);
    }
    return this.client.getInclusionProof(stateId);
  }

  /**
   * Submits a certification request to the aggregator.
   *
   * @param certificationData The certification data to submit.
   * @return certification response from the aggregator.
   */
  public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData certificationData) {
    return this.client.submitCertificationRequest(certificationData);
  }

  /**
   * Submits a certification request with options (an operation and a call timeout).
   *
   * @param certificationData The certification data to submit.
   * @param options request options; a wait is not allowed
   * @return certification response from the aggregator.
   */
  public CompletableFuture<CertificationResponse> submitCertificationRequest(
      CertificationData certificationData, RequestOptions options) {
    if (this.client instanceof StatusAwareAggregatorClient) {
      return ((StatusAwareAggregatorClient) this.client)
          .submitCertificationRequest(certificationData, options);
    }
    return this.client.submitCertificationRequest(certificationData);
  }

  /**
   * Gets the proofs of a batch of states from one finalized view. A client without {@link
   * BatchAggregatorClient} completes with {@link BatchProofException.Kind#UNSUPPORTED}; a batch is
   * never emulated with single calls.
   *
   * @param stateIds 1..256 distinct state ids
   * @param options exact root or latest view, optional wait, optional operation
   * @return batch in request order
   */
  public CompletableFuture<InclusionProofBatch> getInclusionProofs(List<StateId> stateIds,
      BatchRequestOptions options) {
    if (this.client instanceof BatchAggregatorClient) {
      return ((BatchAggregatorClient) this.client).getInclusionProofs(stateIds, options);
    }
    return CompletableFuture.failedFuture(new BatchProofException(
        BatchProofException.Kind.UNSUPPORTED,
        "The aggregator client does not support batch inclusion proofs.", "no_batch_capability",
        false));
  }

  /**
   * Gets the non-inclusion proof of a state. The proof is not verified. A client without {@link
   * NonInclusionAggregatorClient} completes with {@link NonInclusionProofException.Kind#REJECTED}.
   *
   * <p>Non-inclusion proves absence only at the authenticated snapshot returned by verification.
   * It does not prove absence now, continuous absence, or that a token can be spent. Compare the
   * snapshot's network and root round (or its certified seal timestamp under your application's
   * time policy) with an independently trusted requirement. The seal timestamp is a
   * consensus-certified time, not proof of real-time freshness. Do not use unverified response
   * metadata for this check.
   *
   * @param stateId the state asked about
   * @param options optional operation; a wait is not allowed
   * @return the unverified proof, or the service's claim that the state is included
   */
  public CompletableFuture<NonInclusionProofResponse> getNonInclusionProof(StateId stateId,
      RequestOptions options) {
    if (this.client instanceof NonInclusionAggregatorClient) {
      return ((NonInclusionAggregatorClient) this.client).getNonInclusionProof(stateId, options);
    }
    return CompletableFuture.failedFuture(new NonInclusionProofException(
        NonInclusionProofException.Kind.REJECTED,
        "The aggregator client does not support non-inclusion proofs.", null, null, false));
  }

  /**
   * Submits a certification request idempotently.
   *
   * <p>A certification request is signed and deterministic, so sending it again is the same request
   * and the aggregator never creates a second leaf for it. This returns {@link
   * SubmitOutcome.Kind#ALREADY_SUBMITTED} instead of an error when the aggregator already holds a
   * record for the state id: a Mongo backed queue rejects a copy of a still-pending request with an
   * internal error (code -32603) whose data names a duplicate key (recognized by that text, the
   * server has no dedicated code), and a state that is already certified is reported as {@code
   * STATE_ID_EXISTS}. Both reports are by state id only, so a different request for the same state,
   * such as a conflicting spend, is reported the same way: wait for the proof and compare it.
   * Every other rejection, such as {@code REQUEST_EXPIRED}, completes exceptionally with {@link
   * CertificationRejectedException}. The plain {@link #submitCertificationRequest} reports both
   * duplicate responses as given.
   *
   * @param certificationData the certification data to submit
   * @param options request options (an operation, a call timeout)
   * @return the outcome
   */
  public CompletableFuture<SubmitOutcome> resubmitCertificationRequest(
      CertificationData certificationData, RequestOptions options) {
    CompletableFuture<SubmitOutcome> result = new CompletableFuture<>();
    CompletableFuture<CertificationResponse> call =
        this.submitCertificationRequest(certificationData, options);
    call.whenComplete((response, error) -> {
      if (error != null) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause() : error;
        if (isDuplicateSubmission(cause)) {
          result.complete(new SubmitOutcome(SubmitOutcome.Kind.ALREADY_SUBMITTED,
              CertificationResponse.create(CertificationStatus.STATE_ID_QUEUED)));
        } else {
          result.completeExceptionally(cause);
        }
        return;
      }
      CertificationStatus status = response.getStatus();
      if (CertificationStatus.SUCCESS.equals(status)) {
        result.complete(new SubmitOutcome(SubmitOutcome.Kind.ACCEPTED, response));
      } else if (CertificationStatus.STATE_ID_EXISTS.equals(status)
          || CertificationStatus.ALREADY_SUBMITTED.equals(status)
          || CertificationStatus.STATE_ID_QUEUED.equals(status)) {
        result.complete(new SubmitOutcome(SubmitOutcome.Kind.ALREADY_SUBMITTED, response));
      } else {
        result.completeExceptionally(new CertificationRejectedException(status));
      }
    });
    result.whenComplete((value, error) -> {
      if (result.isCancelled()) {
        call.cancel(true);
      }
    });
    return result;
  }

  /**
   * Submits a transaction and waits for its verified proof, resubmitting at most {@code
   * maxResubmits} times after the aggregator hinted the request as unknown or expired.
   *
   * <p>The proof must certify exactly this transaction: another request certified for the state id
   * completes exceptionally with {@link InclusionProofWaitException.Conflict}, after the proof was
   * authenticated, and nothing is resubmitted for it. A proof that does not verify fails the call
   * and is never resubmitted for. The total time of {@code options} covers all attempts.
   *
   * @param transaction the transaction whose request is submitted
   * @param certificationData its certification data
   * @param maxResubmits resubmissions allowed after the first submission, not negative
   * @param trustBase root trust base
   * @param predicateVerifier predicate verifier service
   * @param options wait options; the timeout is the total of the call
   * @return verified inclusion proof
   */
  public CompletableFuture<InclusionProof> submitAndWait(Transaction transaction,
      CertificationData certificationData, int maxResubmits, RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier, WaitOptions options) {
    Objects.requireNonNull(options, "options cannot be null");
    if (maxResubmits < 0) {
      throw new IllegalArgumentException("maxResubmits must not be negative");
    }
    CompletableFuture<InclusionProof> result = new CompletableFuture<>();
    long deadline = System.nanoTime() + options.getTimeout().toNanos();
    attempt(transaction, certificationData, maxResubmits, trustBase, predicateVerifier, options,
        deadline, result, new CompletableFuture<?>[1]);
    return result;
  }

  private void attempt(Transaction transaction, CertificationData data, int resubmitsLeft,
      RootTrustBase trustBase, PredicateVerifierService verifier, WaitOptions options,
      long deadline, CompletableFuture<InclusionProof> result, CompletableFuture<?>[] current) {
    long left = deadline - System.nanoTime();
    if (left <= 0) {
      result.completeExceptionally(new TimeoutException("Timeout waiting for inclusion proof"));
      return;
    }
    CompletableFuture<SubmitOutcome> submit = this.resubmitCertificationRequest(data,
        RequestOptions.none().withCallTimeout(Duration.ofNanos(left)));
    current[0] = submit;
    submit.whenComplete((outcome, error) -> {
      if (error != null) {
        result.completeExceptionally(error);
        return;
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        result.completeExceptionally(new TimeoutException("Timeout waiting for inclusion proof"));
        return;
      }
      CompletableFuture<InclusionProof> wait = InclusionProofUtils.waitInclusionProof(this,
          trustBase, verifier, transaction, options.timeout(Duration.ofNanos(remaining)));
      current[0] = wait;
      wait.whenComplete((proof, waitError) -> {
        if (waitError == null) {
          result.complete(proof);
          return;
        }
        Throwable cause = waitError instanceof CompletionException && waitError.getCause() != null
            ? waitError.getCause() : waitError;
        if (cause instanceof InclusionProofWaitException
            && ((InclusionProofWaitException) cause).isResubmittable() && resubmitsLeft > 0) {
          attempt(transaction, data, resubmitsLeft - 1, trustBase, verifier, options, deadline,
              result, current);
        } else {
          result.completeExceptionally(cause);
        }
      });
    });
    result.whenComplete((value, error) -> {
      if (result.isCancelled() && current[0] != null) {
        current[0].cancel(true);
      }
    });
  }

  private static boolean isDuplicateSubmission(Throwable error) {
    JsonRpcDataException rpc = null;
    if (error instanceof JsonRpcDataException) {
      rpc = (JsonRpcDataException) error;
    } else if (error instanceof JsonRpcNetworkException) {
      rpc = ((JsonRpcNetworkException) error).getRpcError();
    }
    if (rpc == null || rpc.getCode() != -32603) {
      return false;
    }
    // Heuristic: free text of a server internal error. Only a duplicate on the state id index
    // counts (the Mongo error names the index, `index: stateId_1`); a duplicate on any other index
    // is a real failure. A structured server status replaces this once there is one.
    String data = rpc.getData() != null && rpc.getData().isTextual() ? rpc.getData().textValue() : "";
    String text = rpc.getMessage() + " " + data;
    return DUPLICATE_KEY.matcher(text).find() && STATE_ID_INDEX.matcher(text).find();
  }
}
