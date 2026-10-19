package org.unicitylabs.sdk.util;

import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofWaitException;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.WaitOptions;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.internal.InclusionProofWaiter;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.StateMask;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationRule;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationStatus;
import org.unicitylabs.sdk.util.verification.VerificationException;
import org.unicitylabs.sdk.util.verification.VerificationResult;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Utility class for working with inclusion proofs.
 */
public class InclusionProofUtils {

  private InclusionProofUtils() {
  }

  /**
   * Wait for an inclusion proof to be available and verified, with the default options: the
   * aggregator is asked to hold each request while the state is pending (long poll), the first
   * request goes out at once, and the wait falls back to polling at one second when the aggregator
   * does not support the hold. The total time is 30 seconds.
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
    return waitInclusionProof(client, trustBase, predicateVerifier, transaction,
            WaitOptions.defaults());
  }

  /**
   * Wait for an inclusion proof to be available and verified with custom timeout. The long poll is
   * on; the interval is the pace of the polling fallback.
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
    return waitInclusionProof(client, trustBase, predicateVerifier, transaction,
            WaitOptions.defaults().timeout(timeout).interval(interval));
  }

  /**
   * Wait for an inclusion proof to be available and verified.
   *
   * <p>The first request goes out immediately after the call. Failures complete the future
   * exceptionally: {@link java.util.concurrent.TimeoutException} when the total time is used up,
   * {@link InclusionProofWaitException.Expired} and {@link InclusionProofWaitException.Unknown}
   * when the aggregator's unauthenticated hint ends the wait (the same signed request may be
   * submitted again), {@link InclusionProofWaitException.Conflict} when the aggregator certified
   * another request for the state, {@link VerificationException} when the proof does not verify,
   * and the transport or service error otherwise. Cancelling the future cancels the HTTP call in
   * flight and the pending pause. A proof is returned only after it verified.
   *
   * @param client            State transition client
   * @param trustBase         Root trust base
   * @param predicateVerifier Predicate verifier service
   * @param transaction       Transaction to wait Inclusion proof for
   * @param options           Wait options
   * @return Completable future with inclusion proof
   */
  public static CompletableFuture<InclusionProof> waitInclusionProof(
          StateTransitionClient client,
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          Transaction transaction,
          WaitOptions options
  ) {
    StateId stateId = StateId.fromTransaction(transaction);
    return InclusionProofWaiter.await(client.getAggregatorClient(), stateId,
            proof -> check(trustBase, predicateVerifier, transaction, stateId, proof), options);
  }

  private static void check(RootTrustBase trustBase, PredicateVerifierService predicateVerifier,
      Transaction transaction, StateId stateId, InclusionProof proof) {
    VerificationResult<InclusionProofVerificationStatus> result =
            InclusionProofVerificationRule.verify(trustBase, predicateVerifier, proof, transaction);
    InclusionProofVerificationStatus status = result.getStatus();
    if (status == InclusionProofVerificationStatus.OK) {
      return;
    }
    // Another request was certified for this state id: typed, so that a conflicting submission is
    // not reported as a generic verification failure. Definitive only for a proof that is itself
    // valid for the awaited state id.
    if ((status == InclusionProofVerificationStatus.TRANSACTION_HASH_MISMATCH
            || status == InclusionProofVerificationStatus.CERTIFICATION_DATA_MISMATCH)
            && authenticatesAnotherRequest(trustBase, predicateVerifier, stateId, proof)) {
      throw new InclusionProofWaitException.Conflict(status, stateId, proof);
    }
    throw new VerificationException("Inclusion proof verification failed", result);
  }

  private static boolean authenticatesAnotherRequest(RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier, StateId stateId, InclusionProof proof) {
    try {
      CertificationData data = proof.getCertificationData();
      if (!StateId.fromCertificationData(data).equals(stateId)) {
        return false;
      }
      return InclusionProofVerificationRule.verify(trustBase, predicateVerifier, proof,
              new CertifiedRequest(data)).getStatus() == InclusionProofVerificationStatus.OK;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The transaction fields a proof's own certification data certifies, for self-verification. */
  private static final class CertifiedRequest implements Transaction {
    private final CertificationData data;

    CertifiedRequest(CertificationData data) {
      this.data = data;
    }

    @Override
    public EncodedPredicate getLockScript() {
      return data.getLockScript();
    }

    @Override
    public DataHash getSourceStateHash() {
      return data.getSourceStateHash();
    }

    @Override
    public Optional<Long> getExpiresAt() {
      return data.getExpiresAt();
    }

    @Override
    public DataHash calculateTransactionHash() {
      return data.getTransactionHash();
    }

    @Override
    public Optional<byte[]> getData() {
      throw new UnsupportedOperationException();
    }

    @Override
    public EncodedPredicate getRecipient() {
      throw new UnsupportedOperationException();
    }

    @Override
    public StateMask getStateMask() {
      throw new UnsupportedOperationException();
    }

    @Override
    public DataHash calculateStateHash() {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] toCbor() {
      throw new UnsupportedOperationException();
    }
  }
}
