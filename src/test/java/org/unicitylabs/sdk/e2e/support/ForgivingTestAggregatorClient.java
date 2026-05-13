package org.unicitylabs.sdk.e2e.support;

import java.util.concurrent.CompletableFuture;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;

/**
 * Forgiving counterpart to {@link StrictTestAggregatorClient}. Duplicate
 * {@code StateId} submissions return SUCCESS (silently keeping the first
 * leaf). Matches the TS mock's current behavior — used for scenarios that
 * exercise inclusion-proof mismatch detection downstream of a forgiving
 * aggregator (e.g. {@code double-spend-prevention.feature}, the TS
 * {@code token-certification-status.feature} duplicate-mint row).
 *
 * <p>This is purely test-side: Tier D of BDD_MIGRATION_PLAN.md §7.4.4.
 */
public final class ForgivingTestAggregatorClient implements AggregatorClient {

  private final TestAggregatorClient inner;

  private ForgivingTestAggregatorClient(TestAggregatorClient inner) {
    this.inner = inner;
  }

  public static ForgivingTestAggregatorClient create() {
    return new ForgivingTestAggregatorClient(TestAggregatorClient.create());
  }

  public RootTrustBase getTrustBase() {
    return inner.getTrustBase();
  }

  @Override
  public CompletableFuture<CertificationResponse> submitCertificationRequest(
      CertificationData certificationData) {
    // Delegate as-is — inner is idempotent-and-SUCCESS for duplicates, which
    // is the forgiving semantics we want.
    return inner.submitCertificationRequest(certificationData);
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
