package org.unicitylabs.sdk.e2e.support;

import java.util.ArrayList;
import java.util.List;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.functional.payment.TestPaymentData;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;

/**
 * Simulated multi-shard pool. If {@code SHARD_0_URL} env var is present, we
 * would construct JSON-RPC clients against real shards — currently not
 * implemented (stub); hermetic mode uses N independent
 * {@link StrictTestAggregatorClient} instances as "shards".
 *
 * <p>Rationale: the shard-load feature exercises routing / batching logic
 * across multiple aggregators. On real topology the pool points at
 * {@code SHARD_<n>_URL}. Here the pool creates multiple in-memory strict
 * aggregators so the test can verify that batches-per-shard complete without
 * cross-shard interference. Trade-off: each simulated shard has its own
 * trust base, so a full end-to-end verification across shards would need a
 * shared trust base — out of scope for this lean scaffold.
 */
public final class SimulatedShardPool {

  private final List<StrictTestAggregatorClient> shards;
  private final List<StateTransitionClient> clients;
  private final List<PredicateVerifierService> verifiers;
  private final List<MintJustificationVerifierService> mjvs;
  private final List<RootTrustBase> trustBases;

  private SimulatedShardPool(
      List<StrictTestAggregatorClient> shards,
      List<StateTransitionClient> clients,
      List<PredicateVerifierService> verifiers,
      List<MintJustificationVerifierService> mjvs,
      List<RootTrustBase> trustBases) {
    this.shards = shards;
    this.clients = clients;
    this.verifiers = verifiers;
    this.mjvs = mjvs;
    this.trustBases = trustBases;
  }

  public static SimulatedShardPool create(int shardCount) {
    List<StrictTestAggregatorClient> shards = new ArrayList<>();
    List<StateTransitionClient> clients = new ArrayList<>();
    List<PredicateVerifierService> verifiers = new ArrayList<>();
    List<MintJustificationVerifierService> mjvs = new ArrayList<>();
    List<RootTrustBase> trustBases = new ArrayList<>();
    for (int i = 0; i < shardCount; i++) {
      StrictTestAggregatorClient shard = StrictTestAggregatorClient.create();
      shards.add(shard);
      clients.add(new StateTransitionClient(shard));
      trustBases.add(shard.getTrustBase());
      PredicateVerifierService predVer = PredicateVerifierService.create();
      verifiers.add(predVer);
      MintJustificationVerifierService mjv = new MintJustificationVerifierService();
      mjv.register(new SplitMintJustificationVerifier(
          shard.getTrustBase(), predVer, TestPaymentData::decode));
      mjvs.add(mjv);
    }
    return new SimulatedShardPool(shards, clients, verifiers, mjvs, trustBases);
  }

  public int size() {
    return shards.size();
  }

  public StateTransitionClient clientFor(int shardIndex) {
    return clients.get(shardIndex);
  }

  public RootTrustBase trustBaseFor(int shardIndex) {
    return trustBases.get(shardIndex);
  }

  public PredicateVerifierService verifierFor(int shardIndex) {
    return verifiers.get(shardIndex);
  }

  public MintJustificationVerifierService mjvFor(int shardIndex) {
    return mjvs.get(shardIndex);
  }

  public static boolean hasRealTopology() {
    return System.getenv("SHARD_0_URL") != null;
  }
}
