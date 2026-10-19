package org.unicitylabs.sdk.consolidation;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.BatchAggregatorClient;
import org.unicitylabs.sdk.api.BatchProofException;
import org.unicitylabs.sdk.api.BatchRequestOptions;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofBatch;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.LeafValue;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.AggregationPartition;
import org.unicitylabs.sdk.api.bft.AuthenticatedPartitionView;
import org.unicitylabs.sdk.api.bft.PartitionView;
import org.unicitylabs.sdk.api.bft.PartitionViewAuthenticator;
import org.unicitylabs.sdk.api.bft.PartitionViewException;
import org.unicitylabs.sdk.api.bft.PartitionViewLeaf;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.api.bft.verification.OperationUnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.SealVerificationResult;
import org.unicitylabs.sdk.api.bft.verification.SealVerificationSession;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateFailure;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerificationResult;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.internal.OperationContext;
import org.unicitylabs.sdk.util.UnsignedLongs;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Master-first consolidation of original outer-history proofs into proofs under one master seal.
 *
 * <p>One attempt: authenticate the original proofs and derive R_min (the highest root round among
 * them); one {@code latest} batch from the master shard, whose partition view is authenticated
 * strictly under the anchored authority (network, exact epoch, round at least the epoch start,
 * quorum) and must be at least R_min; then one exact-root batch per other participating shard,
 * bounded parallel, at the roots the authenticated view commits to. Every output certificate is
 * derived from the authenticated view and carries the identical master seal; every proof is checked
 * against its original input. One total deadline governs the attempt and cancellation reaches every
 * request. There is no retry, no chunking, no chase of newer seals, an exact root is never replaced
 * by the latest view, a batch is never emulated by single-proof requests, and nothing is ever
 * submitted. A shard whose client has no batch capability makes the attempt
 * {@linkplain ProofUnavailableReason#UNSUPPORTED_CAPABILITY unavailable} before any request.
 *
 * <p>The original proofs are historical: they are authenticated by their quorum signatures and the
 * trusted network (as the ordinary verification of the token they belong to did), not constrained
 * to the current epoch. The target, the seal everything is re-anchored under, is authenticated
 * strictly.
 *
 * <p>The CPU work after a response (decoding, verification) runs on the configured executor, never
 * on a transport callback thread. The returned future completes exceptionally with a
 * {@link ConsolidationException} for cancellation, deadline, transport, server, response and
 * integrity failures; cancelling it cancels the attempt and every outstanding request.
 */
public final class ProofConsolidator {

  private static final int HASH_LENGTH = 32;

  private final ShardRouting routing;
  private final RootTrustBase trustBase;
  private final OperationUnicityCertificateVerifier verifier;
  private final SealVerificationSession session;

  /**
   * Creates a consolidator.
   *
   * @param routing trusted routing of the partition
   * @param trustBase the anchored authority
   * @param verifier verifier of the operation: its seal session for the trust base authenticates the
   *     target strictly, and its memos serve the original proofs (one body check per distinct
   *     certificate, one quorum check per distinct seal); its policy decides how the original proofs
   *     are authenticated. Its pins are not used: the routing's scheme and configuration pins
   *     constrain the original proofs and the targets of the consolidation only
   */
  public ProofConsolidator(ShardRouting routing, RootTrustBase trustBase,
      OperationUnicityCertificateVerifier verifier) {
    this.routing = Objects.requireNonNull(routing, "routing cannot be null");
    this.trustBase = Objects.requireNonNull(trustBase, "trustBase cannot be null");
    this.verifier = Objects.requireNonNull(verifier, "verifier cannot be null");
    this.session = verifier.sessionFor(trustBase);
  }

  /**
   * Runs one attempt. Inputs are never modified.
   *
   * @param items original outer-history proofs
   * @param options attempt options
   * @return rebuilt proofs or an unavailable outcome
   */
  public CompletableFuture<ConsolidationAttempt> consolidate(List<ConsolidationItem> items,
      ConsolidationOptions options) {
    Objects.requireNonNull(items, "items cannot be null");
    Objects.requireNonNull(options, "options cannot be null");
    Run run = new Run(new ArrayList<>(items), options);
    run.start();
    return run.result;
  }

  // ---------------------------------------------------------------------------------------------

  /** Service outcome that ends the attempt as unavailable, internal control flow. */
  private static final class UnavailableSignal extends RuntimeException {
    private final transient Unavailability unavailability;

    UnavailableSignal(ProofUnavailableReason reason, boolean retryable, String detail,
        List<Integer> proofIndexes) {
      super(reason.name(), null, false, false);
      this.unavailability = new Unavailability(reason, retryable, detail, proofIndexes);
    }
  }

  private static final class Group {
    private final ShardEndpoint endpoint;
    private final List<Integer> indexes = new ArrayList<>();

    Group(ShardEndpoint endpoint) {
      this.endpoint = endpoint;
    }
  }

  private static final class ShardBatch {
    private final InclusionProofBatch batch;
    private final Group group;

    ShardBatch(InclusionProofBatch batch, Group group) {
      this.batch = batch;
      this.group = group;
    }
  }

  /** What the replacement certificates are derived from. */
  private interface Target {
    UnicitySeal seal();

    UnicityCertificate certificateOf(ShardId shard);

    PartitionViewLeaf leafOf(ShardId shard);
  }

  private static final class Evidence {
    private final long round;
    private final ShardId shard;
    private final org.unicitylabs.sdk.api.NetworkId network;

    Evidence(long round, ShardId shard, org.unicitylabs.sdk.api.NetworkId network) {
      this.round = round;
      this.shard = shard;
      this.network = network;
    }
  }

  private final class Run {
    private final List<ConsolidationItem> items;
    private final ConsolidationOptions options;
    private final CompletableFuture<ConsolidationAttempt> result = new CompletableFuture<>();
    private final OperationContext attempt;
    private final Executor executor;
    private final AtomicReference<ConsolidationException> terminal = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private long evidenceQuorumChecks;
    private long evidenceBodyChecks;
    private long viewBytes;

    Run(List<ConsolidationItem> items, ConsolidationOptions options) {
      this.items = items;
      this.options = options;
      Duration budget = options.getTimeout();
      OperationContext external = options.getOperation();
      if (external != null && external.remaining().compareTo(budget) < 0) {
        budget = external.remaining();
      }
      this.attempt = OperationContext.withBudget(budget);
      this.executor = options.getExecutor() != null ? options.getExecutor()
          : ForkJoinPool.commonPool();
      if (external != null) {
        OperationContext.Registration registration = external.onCancel(this::cancelledByCaller);
        result.whenComplete((r, e) -> registration.remove());
      }
      // cancelling the returned future cancels the attempt and every outstanding request
      result.whenComplete((r, e) -> {
        if (result.isCancelled()) {
          terminal.compareAndSet(null, new ConsolidationException(
              ConsolidationException.Kind.CANCELLED, "The attempt was cancelled.", null, null,
              false, null, null));
        }
        cancelAttempt();
      });
    }

    void start() {
      long nanos = attempt.remaining().toNanos();
      CompletableFuture.delayedExecutor(nanos, TimeUnit.NANOSECONDS, executor)
          .execute(this::deadline);
      try {
        stage1();
      } catch (Throwable t) {
        fail(t);
      }
    }

    // --- terminal handling ---------------------------------------------------------------------

    private void cancelAttempt() {
      try {
        attempt.cancel();
      } catch (RuntimeException e) {
        // a cancellation action failed; the others ran, the attempt is cancelled either way
      }
    }

    private void cancelledByCaller() {
      ConsolidationException cancelled = new ConsolidationException(
          ConsolidationException.Kind.CANCELLED, "The attempt was cancelled.", null, null, false,
          null, null);
      terminal.compareAndSet(null, cancelled);
      result.completeExceptionally(terminal.get());
    }

    private void deadline() {
      if (result.isDone()) {
        return;
      }
      ConsolidationException timeout = new ConsolidationException(
          ConsolidationException.Kind.TIMEOUT, "The attempt deadline expired.", null, null, true,
          null, null);
      terminal.compareAndSet(null, timeout);
      result.completeExceptionally(terminal.get());
    }

    /** The typed deadline or cancellation error once the attempt is done, else null. */
    private ConsolidationException done() {
      ConsolidationException known = terminal.get();
      if (known != null) {
        return known;
      }
      if (attempt.isExpired()) {
        return new ConsolidationException(ConsolidationException.Kind.TIMEOUT,
            "The attempt deadline expired.", null, null, true, null, null);
      }
      if (attempt.isCancelled() || result.isDone()) {
        return new ConsolidationException(ConsolidationException.Kind.CANCELLED,
            "The attempt was cancelled.", null, null, false, null, null);
      }
      return null;
    }

    private void throwIfDone() {
      ConsolidationException done = done();
      if (done != null) {
        throw done;
      }
    }

    private void fail(Throwable raised) {
      Throwable t = unwrap(raised);
      ConsolidationException done = done();
      if (done != null && (t instanceof CancellationException || !(t instanceof ConsolidationException)
          || ((ConsolidationException) t).getKind() == ConsolidationException.Kind.CANCELLED
          || ((ConsolidationException) t).getKind() == ConsolidationException.Kind.TIMEOUT)) {
        result.completeExceptionally(done);
        cancelAttempt();
        return;
      }
      if (t instanceof UnavailableSignal) {
        result.complete(ConsolidationAttempt.unavailable(((UnavailableSignal) t).unavailability));
        cancelAttempt();
        return;
      }
      if (t instanceof ConsolidationException) {
        result.completeExceptionally(t);
      } else {
        result.completeExceptionally(new ConsolidationException(
            ConsolidationException.Kind.INVALID_RESPONSE,
            "Unexpected failure: " + t, ConsolidationOutcome.INVALID_INPUT, null, false, null, t));
      }
      cancelAttempt();
    }

    private Throwable unwrap(Throwable t) {
      Throwable current = t;
      while ((current instanceof CompletionException || current instanceof ExecutionException)
          && current.getCause() != null) {
        current = current.getCause();
      }
      return current;
    }

    // --- stages --------------------------------------------------------------------------------

    private void stage1() {
      if (items.isEmpty()) {
        throw input("No proofs to consolidate.");
      }
      throwIfDone();
      List<StateId> stateIds = new ArrayList<>(items.size());
      Set<String> keys = new HashSet<>();
      for (ConsolidationItem item : items) {
        StateId id = StateId.fromCertificationData(item.getCertificationData());
        if (!keys.add(id.toString())) {
          throw input("Duplicate state in the proofs to consolidate.");
        }
        stateIds.add(id);
      }
      List<Group> groups = group(stateIds);

      // the capability is a precondition of the whole attempt: a missing or limited batch service
      // is not emulated and nothing is probed
      for (Group group : groups) {
        if (!(group.endpoint.getClient() instanceof BatchAggregatorClient)) {
          throw new UnavailableSignal(ProofUnavailableReason.UNSUPPORTED_CAPABILITY, false,
              group.endpoint.getShard().toString(), null);
        }
        if (group.indexes.size() > BatchAggregatorClient.BATCH_MAX_STATE_IDS) {
          throw new UnavailableSignal(ProofUnavailableReason.LIMIT_EXCEEDED, false, "state_count",
              null);
        }
      }

      long quorumBefore = session.getStats().getQuorumChecks();
      Evidence bound = authenticateEvidence(stateIds);
      evidenceQuorumChecks = session.getStats().getQuorumChecks() - quorumBefore;
      throwIfDone();

      Group master = pickMaster(groups, options.getMaster(), bound.shard);
      fetch(master, stateIds, null, attempt, true)
          .thenApplyAsync(masterBatch -> {
            stage2(groups, stateIds, bound, master, masterBatch);
            return null;
          }, executor)
          .exceptionally(error -> {
            fail(error);
            return null;
          });
    }

    private void stage2(List<Group> groups, List<StateId> stateIds, Evidence bound, Group master,
        ShardBatch masterBatch) {
      try {
        throwIfDone();
        // the one retry without the wait, if the aggregator did not know it, is a request too
        requests.addAndGet(masterBatch.batch.getRequests());
        byte[] viewBytes = masterBatch.batch.getPartitionViewBytes();
        ShardId masterShard = master.endpoint.getShard();
        UnicityCertificate enclosing = commonUc(masterBatch, masterShard);
        // decided on the SERVED certificate: a sharded partition is never consolidated without its
        // authenticated view, and only a certificate of the empty shard (the partition's single
        // leaf) may do without one. A certificate that contradicts the trusted routing is an
        // integrity failure.
        if (viewBytes == null && !enclosing.getShardTreeCertificate().getShard().isEmpty()) {
          throw new UnavailableSignal(ProofUnavailableReason.PARTITION_VIEW_UNAVAILABLE, true,
              masterShard.toString(), null);
        }
        if (!enclosing.getShardTreeCertificate().getShard().equals(masterShard)) {
          throw integrity(ConsolidationOutcome.BINDING_MISMATCH,
              "The master certificate is of another shard.", masterShard, null);
        }

        long quorumBefore = session.getStats().getQuorumChecks();
        Target target = viewBytes != null
            ? authenticateView(viewBytes, enclosing, masterShard)
            : authenticateDirect(enclosing, masterShard, groups.size());
        long quorumChecks = session.getStats().getQuorumChecks() - quorumBefore;
        if (!target.seal().getNetworkId().equals(bound.network)) {
          throw integrity(ConsolidationOutcome.INCOMPATIBLE_LINEAGE,
              "The master seal is of another network than the original proofs.", null, null);
        }
        long masterRound = target.seal().getRootChainRoundNumber();
        if (UnsignedLongs.lessThan(masterRound, bound.round)) {
          // a valid seal that does not cover the history yet: the service has not published far
          // enough
          throw new UnavailableSignal(ProofUnavailableReason.PENDING_PUBLICATION, true,
              "master_round_below_required", null);
        }
        throwIfDone();

        // wave 2: exact roots of the other participating shards, bounded parallel
        List<Group> peers = new ArrayList<>();
        for (Group group : groups) {
          if (group != master) {
            peers.add(group);
          }
        }
        List<DataHash> roots = new ArrayList<>(peers.size());
        for (Group peer : peers) {
          PartitionViewLeaf leaf;
          try {
            leaf = target.leafOf(peer.endpoint.getShard());
          } catch (PartitionViewException e) {
            throw integrity(ConsolidationOutcome.BINDING_MISMATCH,
                "A participating shard is not in the master view.", peer.endpoint.getShard(), e);
          }
          byte[] root = leaf.getInputRecord().getHash();
          if (root == null || root.length != HASH_LENGTH) {
            throw integrity(ConsolidationOutcome.UNSUPPORTED,
                "A participating shard has no 32-byte state root in the master view.",
                peer.endpoint.getShard(), null);
          }
          roots.add(new DataHash(HashAlgorithm.SHA256, root));
        }

        fetchAll(peers, roots, stateIds)
            .thenApplyAsync(peerBatches -> {
              stage3(stateIds, bound, masterShard, masterRound, quorumChecks, masterBatch, peers,
                  target, peerBatches);
              return null;
            }, executor)
            .exceptionally(error -> {
              fail(error);
              return null;
            });
      } catch (Throwable t) {
        fail(t);
      }
    }

    private void stage3(List<StateId> stateIds, Evidence bound, ShardId masterShard,
        long masterRound, long quorumChecks, ShardBatch masterBatch, List<Group> peers,
        Target target, List<ShardBatch> peerBatches) {
      try {
        requests.addAndGet(peers.size());
        throwIfDone();

        // every shard under the identical master seal
        List<ShardBatch> batches = new ArrayList<>();
        batches.add(masterBatch);
        batches.addAll(peerBatches);
        InclusionProofResponse[] responses = new InclusionProofResponse[items.size()];
        long bodyChecks = 0;
        long leafChecks = 0;
        for (ShardBatch shardBatch : batches) {
          ShardId shard = shardBatch.group.endpoint.getShard();
          UnicityCertificate targetCertificate = target.certificateOf(shard);
          // the certificates handed on are held to the same rules as any verified one: the view
          // authenticates the commitment, not the structure of each leaf's record
          bodyChecks++;
          UnicityCertificateVerificationResult body = verifier.verifyBody(targetCertificate);
          if (body.getStatus() != VerificationStatus.OK) {
            throw certificateFailure("Target certificate", body.getFailure(), shard, null);
          }
          checkExactRoot(shardBatch, targetCertificate, shard);
          List<InclusionProofResponse> proofs = shardBatch.batch.getProofs();
          for (int position = 0; position < shardBatch.group.indexes.size(); position++) {
            int index = shardBatch.group.indexes.get(position);
            leafChecks++;
            responses[index] = replace(items.get(index), stateIds.get(index),
                proofs.get(position), targetCertificate, shard);
          }
        }
        throwIfDone();

        ConsolidationCounters counters = new ConsolidationCounters(bodyChecks, evidenceBodyChecks,
            leafChecks, evidenceQuorumChecks, quorumChecks, viewBytes);
        ConsolidatedProofs proofs = new ConsolidatedProofs(counters, masterRound, masterShard,
            requests.get(), bound.round, Arrays.asList(responses), batches.size(),
            peers.isEmpty() ? 1 : 2);
        result.complete(ConsolidationAttempt.consolidated(proofs));
        cancelAttempt();
      } catch (Throwable t) {
        fail(t);
      }
    }

    // --- evidence ------------------------------------------------------------------------------

    /**
     * Authenticates every original proof (shard and partition path to its seal hash, leaf rules,
     * seal quorum under the trusted authority) and derives R_min: the highest root round among
     * them. All seals must be of one network.
     */
    private Evidence authenticateEvidence(List<StateId> stateIds) {
      List<ShardId> order = routing.getOrder();
      Evidence best = null;
      for (int i = 0; i < items.size(); i++) {
        ConsolidationItem item = items.get(i);
        UnicityCertificate uc = item.getProof().getUnicityCertificate();
        ShardId shard = uc.getShardTreeCertificate().getShard();
        if (!order.contains(shard)) {
          throw integrity(ConsolidationOutcome.INCOMPATIBLE_LINEAGE,
              String.format("Evidence %d: shard \"%s\" is not in the topology.", i, shard), null,
              null);
        }
        long bodiesBefore = verifier.getStats().getBodyChecks();
        UnicityCertificateVerificationResult verified = verifier.verifyCertificate(trustBase, uc);
        evidenceBodyChecks += verifier.getStats().getBodyChecks() - bodiesBefore;
        if (verified.getStatus() != VerificationStatus.OK) {
          throw certificateFailure("Evidence " + i, verified.getFailure(), shard, null);
        }
        String pinViolation = routing.getViewPolicy().violation(shard,
            uc.getShardConfigurationHash());
        if (pinViolation != null) {
          throw integrity(ConsolidationOutcome.INCOMPATIBLE_LINEAGE,
              String.format("Evidence %d: %s", i, pinViolation), shard, null);
        }
        leafRules(item, stateIds.get(i), item.getProof(), uc, shard);

        UnicitySeal seal = uc.getUnicitySeal();
        if (best != null && !seal.getNetworkId().equals(best.network)) {
          throw integrity(ConsolidationOutcome.INCOMPATIBLE_LINEAGE,
              String.format("Evidence %d: seal of another network than earlier evidence.", i),
              null, null);
        }
        if (best == null || UnsignedLongs.compare(seal.getRootChainRoundNumber(), best.round) > 0) {
          best = new Evidence(seal.getRootChainRoundNumber(), shard, seal.getNetworkId());
        }
      }
      return best;
    }

    /** Maps a failed certificate verification to the failure of the consolidation. */
    private ConsolidationException certificateFailure(String what,
        UnicityCertificateFailure failure, ShardId shard, Throwable cause) {
      ConsolidationOutcome outcome;
      switch (failure == null ? UnicityCertificateFailure.BODY_INVALID : failure) {
        case SEAL_HASH_MISMATCH:
          outcome = ConsolidationOutcome.BINDING_MISMATCH;
          break;
        case NETWORK_MISMATCH:
        case EPOCH_MISMATCH:
        case BEFORE_EPOCH_START:
        case QUORUM_NOT_REACHED:
        case UNSUPPORTED_AUTHORITY:
          outcome = ConsolidationOutcome.UNAUTHENTICATED;
          break;
        default:
          outcome = ConsolidationOutcome.INCOMPATIBLE_LINEAGE;
          break;
      }
      return ConsolidationException.of(ConsolidationException.Kind.INTEGRITY, outcome,
          what + ": " + failure, shard, cause);
    }

    // --- targets -------------------------------------------------------------------------------

    /** The master target from its authenticated partition view. */
    private Target authenticateView(byte[] viewBytes, UnicityCertificate enclosing,
        ShardId masterShard) {
      this.viewBytes += viewBytes.length;
      PartitionViewAuthenticator authenticator =
          new PartitionViewAuthenticator(session, routing.getViewPolicy());
      AuthenticatedPartitionView view;
      try {
        view = authenticator.authenticate(PartitionView.fromCbor(viewBytes), enclosing);
      } catch (PartitionViewException e) {
        throw integrity(e.getReason() == PartitionViewException.Reason.AUTHENTICATION
            ? ConsolidationOutcome.UNAUTHENTICATED : ConsolidationOutcome.INVALID_INPUT,
            e.getMessage(), masterShard, e);
      }
      return new Target() {
        @Override
        public UnicitySeal seal() {
          return view.getSeal();
        }

        @Override
        public UnicityCertificate certificateOf(ShardId shard) {
          return view.certificateOf(shard);
        }

        @Override
        public PartitionViewLeaf leafOf(ShardId shard) {
          return view.leafOf(shard);
        }
      };
    }

    /**
     * The master target of an unsharded partition: the ordinary certificate itself. With the empty
     * shard id and no shard tree siblings the shard tree is the one leaf, so the certificate
     * already commits to everything a one-leaf partition view would repeat; no view is received,
     * decoded, authenticated or derived. This is not an optional view: it holds only for a
     * certificate that is for the configured partition, the empty shard (one routed group, no
     * peers, scheme empty) and an empty shard path, and it passes the same pins, structure,
     * seal-hash path and strict seal authentication as any other target. Anything else is an
     * integrity error, never a fallback.
     */
    private Target authenticateDirect(UnicityCertificate enclosing, ShardId masterShard,
        int groupCount) {
      if (!masterShard.isEmpty() || groupCount != 1 || !routing.getScheme().isEmpty()) {
        throw integrity(ConsolidationOutcome.BINDING_MISMATCH,
            "A direct target needs the unsharded partition with one routed shard.", masterShard,
            null);
      }
      if (!enclosing.getShardTreeCertificate().getShard().isEmpty()
          || enclosing.getShardTreeCertificate().getSiblingHashCount() != 0) {
        throw integrity(ConsolidationOutcome.BINDING_MISMATCH,
            "A certificate without partition view must have the empty shard and path.",
            masterShard, null);
      }
      UnicityCertificateVerificationResult body = verifier.verifyBody(enclosing);
      if (body.getStatus() != VerificationStatus.OK) {
        throw certificateFailure("Direct target", body.getFailure(), masterShard, null);
      }
      String pinViolation = routing.getViewPolicy().violation(masterShard,
          enclosing.getShardConfigurationHash());
      if (pinViolation != null) {
        throw integrity(ConsolidationOutcome.INCOMPATIBLE_LINEAGE, "Direct target: " + pinViolation,
            masterShard, null);
      }
      SealVerificationResult authenticated = session.authenticate(enclosing.getUnicitySeal());
      if (authenticated.getStatus() != VerificationStatus.OK) {
        throw integrity(ConsolidationOutcome.UNAUTHENTICATED,
            "Authenticating unicity seal: " + authenticated.getFailure() + ".", masterShard, null);
      }
      return new Target() {
        @Override
        public UnicitySeal seal() {
          return enclosing.getUnicitySeal();
        }

        @Override
        public UnicityCertificate certificateOf(ShardId shard) {
          if (!shard.isEmpty()) {
            throw new PartitionViewException(PartitionViewException.Reason.TOPOLOGY,
                "A direct target has only the empty shard.");
          }
          return enclosing;
        }

        @Override
        public PartitionViewLeaf leafOf(ShardId shard) {
          throw new PartitionViewException(PartitionViewException.Reason.TOPOLOGY,
              "A direct target has no peer shards.");
        }
      };
    }

    // --- requests ------------------------------------------------------------------------------

    private CompletableFuture<ShardBatch> fetch(Group group, List<StateId> stateIds, DataHash root,
        OperationContext scope, boolean master) {
      CompletableFuture<ShardBatch> out = new CompletableFuture<>();
      try {
        throwIfDone();
        List<StateId> ids = new ArrayList<>(group.indexes.size());
        for (int index : group.indexes) {
          ids.add(stateIds.get(index));
        }
        BatchRequestOptions requestOptions;
        if (root != null) {
          // an exact root is never waited on and never replaced by the latest view
          requestOptions = BatchRequestOptions.exact(root);
        } else {
          requestOptions = BatchRequestOptions.latest();
          long wait = master ? masterWait() : 0;
          if (wait > 0) {
            requestOptions = requestOptions.withWaitMillis(wait);
          }
        }
        requestOptions = requestOptions.withOperation(attempt);
        BatchAggregatorClient client = (BatchAggregatorClient) group.endpoint.getClient();
        CompletableFuture<InclusionProofBatch> call = client.getInclusionProofs(ids, requestOptions);
        OperationContext.Registration registration = scope.onCancel(() -> call.cancel(true));
        call.whenComplete((batch, error) -> {
          registration.remove();
          if (error != null) {
            out.completeExceptionally(requestFailure(error, group.endpoint.getShard(), master,
                group.indexes));
            return;
          }
          if (batch == null || batch.getProofs().size() != ids.size()) {
            out.completeExceptionally(new ConsolidationException(
                ConsolidationException.Kind.INVALID_RESPONSE,
                String.format("Got %d proofs for %d states.",
                    batch == null ? 0 : batch.getProofs().size(), ids.size()),
                ConsolidationOutcome.INVALID_INPUT, null, false, group.endpoint.getShard(), null));
            return;
          }
          out.complete(new ShardBatch(batch, group));
        });
        // cancelling the result of this step cancels the call
        out.whenComplete((r, e) -> {
          if (out.isCancelled()) {
            call.cancel(true);
          }
        });
      } catch (Throwable t) {
        out.completeExceptionally(requestFailure(t, group.endpoint.getShard(), master,
            group.indexes));
      }
      return out;
    }

    /** The wait that fits the attempt deadline, or none: {@code min(window, left - margin)}. */
    private long masterWait() {
      if (!options.isMasterLongPoll()) {
        return 0;
      }
      long fit = Math.min(options.getWindow().toMillis(),
          attempt.remaining().toMillis() - options.getMargin().toMillis());
      return Math.max(0, fit);
    }

    /**
     * Exact-root requests with bounded parallelism; the first failure cancels the rest, reported
     * deterministically (the originating failure, not the cancellations it caused).
     */
    private CompletableFuture<List<ShardBatch>> fetchAll(List<Group> groups, List<DataHash> roots,
        List<StateId> stateIds) {
      CompletableFuture<List<ShardBatch>> all = new CompletableFuture<>();
      int count = groups.size();
      if (count == 0) {
        all.complete(Collections.emptyList());
        return all;
      }
      OperationContext inner = OperationContext.unbounded();
      OperationContext.Registration link = attempt.onCancel(inner::cancel);
      ShardBatch[] results = new ShardBatch[count];
      Throwable[] errors = new Throwable[count];
      AtomicInteger next = new AtomicInteger();
      AtomicBoolean aborted = new AtomicBoolean();
      int workers = Math.min(options.getMaxParallel(), count);
      AtomicInteger running = new AtomicInteger(workers);
      Runnable[] worker = new Runnable[1];
      worker[0] = () -> {
        int i = next.getAndIncrement();
        if (i >= count || aborted.get()) {
          if (running.decrementAndGet() == 0) {
            link.remove();
            finishFetch(all, results, errors);
          }
          return;
        }
        fetch(groups.get(i), stateIds, roots.get(i), inner, false).whenComplete((batch, error) -> {
          if (error != null) {
            errors[i] = error;
            aborted.set(true);
            try {
              inner.cancel();
            } catch (RuntimeException e) {
              // the other calls were cancelled; the originating failure is what is reported
            }
          } else {
            results[i] = batch;
          }
          worker[0].run();
        });
      };
      for (int k = 0; k < workers; k++) {
        worker[0].run();
      }
      return all;
    }

    private void finishFetch(CompletableFuture<List<ShardBatch>> all, ShardBatch[] results,
        Throwable[] errors) {
      Throwable origin = null;
      Throwable first = null;
      for (Throwable error : errors) {
        if (error == null) {
          continue;
        }
        Throwable cause = unwrap(error);
        if (first == null) {
          first = cause;
        }
        boolean cancellation = cause instanceof ConsolidationException
            && (((ConsolidationException) cause).getKind() == ConsolidationException.Kind.CANCELLED
            || ((ConsolidationException) cause).getKind() == ConsolidationException.Kind.TIMEOUT);
        if (origin == null && !cancellation) {
          origin = cause;
        }
      }
      if (first != null) {
        all.completeExceptionally(origin != null ? origin : first);
        return;
      }
      all.complete(Collections.unmodifiableList(Arrays.asList(results)));
    }

    /** Maps whatever a failed call produced to the failure or unavailable outcome of the attempt. */
    private Throwable requestFailure(Throwable raised, ShardId shard, boolean master,
        List<Integer> positions) {
      Throwable e = unwrap(raised);
      ConsolidationException done = done();
      if (done != null && (e instanceof CancellationException
          || !(e instanceof ConsolidationException))) {
        return done;
      }
      if (e instanceof ConsolidationException || e instanceof UnavailableSignal) {
        return e;
      }
      if (e instanceof CancellationException) {
        return new ConsolidationException(ConsolidationException.Kind.CANCELLED,
            "The request was cancelled.", null, null, false, shard, e);
      }
      if (!(e instanceof BatchProofException)) {
        return new ConsolidationException(ConsolidationException.Kind.TRANSPORT,
            String.valueOf(e.getMessage()), null, null, true, shard, e);
      }
      BatchProofException batch = (BatchProofException) e;
      String detail = batch.getDetail();
      switch (batch.getKind()) {
        case UNAVAILABLE: {
          String d = detail == null ? "" : detail;
          if (BatchProofException.isStateDetail(d)) {
            // the offending request positions of this shard's batch, as positions in the token's
            // outer history
            List<Integer> proofIndexes = null;
            if (batch.getIndexes() != null) {
              proofIndexes = new ArrayList<>();
              for (int i : batch.getIndexes()) {
                if (i >= 0 && i < positions.size()) {
                  proofIndexes.add(positions.get(i));
                }
              }
            }
            return new UnavailableSignal(ProofUnavailableReason.valueOf(d.toUpperCase()),
                "state_pending".equals(d), d, proofIndexes);
          }
          if (d.startsWith("busy")) {
            return new UnavailableSignal(ProofUnavailableReason.BUSY, true, d, null);
          }
          if (d.equals("partition_view_unavailable")) {
            return new UnavailableSignal(ProofUnavailableReason.PARTITION_VIEW_UNAVAILABLE,
                batch.isRetryable(), d, null);
          }
          if (d.equals("view_not_retained") || (d.equals("state_not_included") && !master)) {
            return new UnavailableSignal(ProofUnavailableReason.TARGET_NOT_RETAINED,
                batch.isRetryable(), d, null);
          }
          return new UnavailableSignal(ProofUnavailableReason.PENDING_PUBLICATION,
              batch.isRetryable(), d, null);
        }
        case UNSUPPORTED:
          return new UnavailableSignal("method_not_found".equals(detail)
              ? ProofUnavailableReason.UNSUPPORTED_CAPABILITY
              : ProofUnavailableReason.UNSUPPORTED_CONFIGURATION, false, detail, null);
        case LIMIT_EXCEEDED:
          return new UnavailableSignal(ProofUnavailableReason.LIMIT_EXCEEDED, false, detail, null);
        case REJECTED:
          return new ConsolidationException(ConsolidationException.Kind.REJECTED, batch.getMessage(),
              null, detail, false, shard, batch);
        case SERVER:
          return new ConsolidationException(ConsolidationException.Kind.SERVER, batch.getMessage(),
              null, detail, batch.isRetryable(), shard, batch);
        case INVALID_RESPONSE:
          return new ConsolidationException(ConsolidationException.Kind.INVALID_RESPONSE,
              batch.getMessage(), ConsolidationOutcome.INVALID_INPUT, detail, false, shard, batch);
        default:
          return new ConsolidationException(ConsolidationException.Kind.TRANSPORT,
              batch.getMessage(), null, detail, true, shard, batch);
      }
    }

    // --- per input rules -----------------------------------------------------------------------

    /** Route every state to its endpoint, groups in canonical shard order, indexes in input order. */
    private List<Group> group(List<StateId> stateIds) {
      java.util.Map<String, Group> byShard = new java.util.LinkedHashMap<>();
      for (int index = 0; index < stateIds.size(); index++) {
        ShardEndpoint endpoint = routing.route(stateIds.get(index));
        Group group = byShard.computeIfAbsent(endpoint.getShard().toString(),
            key -> new Group(endpoint));
        group.indexes.add(index);
      }
      List<Group> groups = new ArrayList<>();
      for (ShardId id : routing.getOrder()) {
        Group group = byShard.get(id.toString());
        if (group != null) {
          groups.add(group);
        }
      }
      return groups;
    }

    private Group pickMaster(List<Group> groups, ShardId preferred, ShardId newest) {
      ShardId want = preferred != null ? preferred : newest;
      for (Group group : groups) {
        if (group.endpoint.getShard().equals(want)) {
          return group;
        }
      }
      throw input(String.format("Master shard \"%s\" does not take part.", want));
    }

    /**
     * The per input rules, shared by the original proofs and the replacements: expiry, reference
     * time not after the certificate's timestamp, shard membership and the path to the target
     * state root.
     */
    private void leafRules(ConsolidationItem item, StateId stateId, InclusionProof proof,
        UnicityCertificate target, ShardId shard) {
      java.util.Optional<Long> expiresAt = item.getCertificationData().getExpiresAt();
      long referenceTime = proof.getReferenceTime();
      if (expiresAt.isPresent() && UnsignedLongs.compare(referenceTime, expiresAt.get()) >= 0) {
        throw integrity(ConsolidationOutcome.EXPIRED, String.format(
            "%s: reference time %s reaches expiry %s.", stateId,
            UnsignedLongs.toDecimal(referenceTime), UnsignedLongs.toDecimal(expiresAt.get())),
            shard, null);
      }
      if (UnsignedLongs.compare(referenceTime, target.getInputRecord().getTimestamp()) > 0) {
        throw integrity(ConsolidationOutcome.REFERENCE_TIME_AFTER_TARGET, String.format(
            "%s: reference time %s is after the target timestamp.", stateId,
            UnsignedLongs.toDecimal(referenceTime)), shard, null);
      }
      ShardId targetShard = target.getShardTreeCertificate().getShard();
      if (!targetShard.isEmpty() && !targetShard.isPrefixOf(stateId.getData())) {
        throw integrity(ConsolidationOutcome.BINDING_MISMATCH,
            String.format("%s does not belong to shard \"%s\".", stateId, shard), shard, null);
      }
      byte[] root = target.getInputRecord().getHash();
      if (root == null || root.length != HASH_LENGTH) {
        throw integrity(ConsolidationOutcome.UNSUPPORTED,
            String.format("The target state root has %s bytes.",
                root == null ? "no" : Integer.toString(root.length)), shard, null);
      }
      DataHash leafValue = LeafValue.calculate(item.getCertificationData().getTransactionHash(),
          referenceTime);
      if (!proof.getInclusionCertificate().verify(stateId, leafValue,
          new DataHash(HashAlgorithm.SHA256, root))) {
        throw integrity(ConsolidationOutcome.BINDING_MISMATCH,
            String.format("%s: the proof path does not reach the target root.", stateId), shard,
            null);
      }
    }

    /** The batch's elements as certified proofs carrying byte-identical local certificates. */
    private UnicityCertificate commonUc(ShardBatch shardBatch, ShardId shard) {
      List<InclusionProofResponse> proofs = shardBatch.batch.getProofs();
      byte[] first = null;
      for (int i = 0; i < proofs.size(); i++) {
        InclusionProofResponse response = proofs.get(i);
        if (response == null || response.getInclusionProof() == null) {
          throw new ConsolidationException(ConsolidationException.Kind.INVALID_RESPONSE,
              String.format("Proof %d of shard \"%s\" is empty.", i, shard),
              ConsolidationOutcome.INVALID_INPUT, null, false, shard, null);
        }
        byte[] bytes = response.getInclusionProof().getUnicityCertificate().toCbor();
        if (first != null && !Arrays.equals(first, bytes)) {
          throw integrity(ConsolidationOutcome.INVALID_INPUT,
              String.format("Proof %d carries a different unicity certificate.", i), shard, null);
        }
        first = bytes;
      }
      return proofs.get(0).getInclusionProof().getUnicityCertificate();
    }

    /** The source proofs are rooted at the state the master view commits to, for this shard. */
    private void checkExactRoot(ShardBatch shardBatch, UnicityCertificate target, ShardId shard) {
      UnicityCertificate local = commonUc(shardBatch, shard);
      byte[] localRoot = local.getInputRecord().getHash();
      byte[] targetRoot = target.getInputRecord().getHash();
      if (localRoot == null || localRoot.length != HASH_LENGTH || targetRoot == null
          || targetRoot.length != HASH_LENGTH || !Arrays.equals(localRoot, targetRoot)) {
        throw integrity(ConsolidationOutcome.BINDING_MISMATCH,
            "The source root differs from the master view root.", shard, null);
      }
      if (!local.getShardTreeCertificate().getShard().equals(shard)
          || local.getUnicityTreeCertificate().getPartitionIdentifierUnsigned()
          != AggregationPartition.AGGREGATION_PARTITION_ID) {
        throw integrity(ConsolidationOutcome.BINDING_MISMATCH,
            "The source certificate identity differs from the shard.", shard, null);
      }
    }

    /** One replacement proof: original certification data and reference time, proof path of the response, target UC. */
    private InclusionProofResponse replace(ConsolidationItem item, StateId stateId,
        InclusionProofResponse response, UnicityCertificate target, ShardId shard) {
      InclusionProof source = response.getInclusionProof();
      if (!Arrays.equals(source.getCertificationData().toCbor(),
          item.getCertificationData().toCbor())) {
        throw integrity(ConsolidationOutcome.BINDING_MISMATCH,
            String.format("%s: the proof is not bound to the original input.", stateId), shard,
            null);
      }
      InclusionProof replacement = source.withUnicityCertificate(target);
      leafRules(item, stateId, replacement, target, shard);
      // the reference time is part of the committed leaf: a proof of another leaf value is not the
      // original's
      if (source.getReferenceTime() != item.getProof().getReferenceTime()) {
        throw integrity(ConsolidationOutcome.BINDING_MISMATCH,
            String.format("%s: the reference time differs from the original.", stateId), shard,
            null);
      }
      return InclusionProofResponse.certified(response.getBlockNumber(), replacement);
    }

    private ConsolidationException input(String message) {
      return ConsolidationException.of(ConsolidationException.Kind.INPUT,
          ConsolidationOutcome.INVALID_INPUT, message, null, null);
    }

    private ConsolidationException integrity(ConsolidationOutcome outcome, String message,
        ShardId shard, Throwable cause) {
      return ConsolidationException.of(ConsolidationException.Kind.INTEGRITY, outcome, message,
          shard, cause);
    }
  }
}
