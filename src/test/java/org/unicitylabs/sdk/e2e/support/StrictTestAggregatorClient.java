package org.unicitylabs.sdk.e2e.support;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;

/**
 * BDD-owned strict wrapper around {@link TestAggregatorClient} that matches the
 * production aggregator's behavior: the second and subsequent submissions for
 * any {@link StateId} return {@link CertificationStatus#STATE_ID_EXISTS}
 * instead of a silent SUCCESS no-op.
 *
 * <p>Rationale: the shared {@code TestAggregatorClient} in the SDK repo silently
 * accepts duplicate submissions, which diverges from the production contract
 * encoded in {@code CertificationStatus.STATE_ID_EXISTS}. Rather than modify
 * the SDK-owned mock (breaking {@code FunctionalCommonFlowTest} etc.), BDD
 * tests construct this wrapper and get production-faithful behavior. See
 * BDD_MIGRATION_PLAN.md §7.2 for the notification owed to SDK authors.
 *
 * <p>This wrapper depends only on the SDK's public {@code AggregatorClient}
 * interface — no internal coupling.
 */
public final class StrictTestAggregatorClient implements AggregatorClient {

  private final TestAggregatorClient inner;
  private final Set<StateId> acceptedStateIds = ConcurrentHashMap.newKeySet();

  private StrictTestAggregatorClient(TestAggregatorClient inner) {
    this.inner = inner;
  }

  public static StrictTestAggregatorClient create() {
    return new StrictTestAggregatorClient(TestAggregatorClient.create());
  }

  public RootTrustBase getTrustBase() {
    return inner.getTrustBase();
  }

  @Override
  public CompletableFuture<CertificationResponse> submitCertificationRequest(
      CertificationData certificationData) {
    StateId stateId = StateId.fromCertificationData(certificationData);
    if (acceptedStateIds.contains(stateId)) {
      return CompletableFuture.completedFuture(
          CertificationResponse.create(CertificationStatus.STATE_ID_EXISTS));
    }
    return inner.submitCertificationRequest(certificationData)
        .thenApply(response -> {
          if (response.getStatus() == CertificationStatus.SUCCESS) {
            acceptedStateIds.add(stateId);
          }
          return response;
        });
  }

  @Override
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
    return inner.getInclusionProof(stateId);
  }

  @Override
  public CompletableFuture<Long> getBlockHeight() {
    return inner.getBlockHeight();
  }
}
