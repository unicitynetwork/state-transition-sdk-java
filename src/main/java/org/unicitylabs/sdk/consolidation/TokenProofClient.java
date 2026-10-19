package org.unicitylabs.sdk.consolidation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.verification.OperationUnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;
import org.unicitylabs.sdk.internal.Deadlines;
import org.unicitylabs.sdk.internal.OperationContext;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.CertifiedTransferTransaction;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Explicit preparation of a token for verification by an EVM contract (including internal bridge
 * back) or a zkVM: returns an ordinary token whose replaceable outer-history proofs all share one
 * unicity seal, so that the target verifier authenticates that seal once.
 *
 * <p>Consolidation is optional and never automatic: nothing in the SDK calls this class. It needs
 * no signing key, never submits anything and never modifies its input. Proofs inside signed mint
 * justifications, split/burn dependencies and opaque predicate data are not replaceable and stay
 * byte for byte unchanged, so a token with nested proofs can still need other seals; the one-seal
 * claim is about the outer history only. The size of the token does not shrink.
 *
 * <p>One attempt makes one {@code latest} request to the master shard and one exact-root request to
 * every other participating shard (in parallel, bounded), under one total deadline. If the proofs
 * already share a seal the verified token is returned without any request. The token is verified
 * before and, rebuilt, after, with one operation of the caller's {@link VerificationContext}: the
 * seal checks of the original verification are not repeated.
 */
public final class TokenProofClient {

  private final ShardRouting routing;
  private final VerificationContext context;

  /**
   * Creates a client.
   *
   * @param routing trusted shard routing, configured once; endpoints and credentials come from here
   *     only
   * @param context the existing verification context: its trust base is the anchored authority and
   *     its predicate, issuance and certificate verification are what decide. A stock verifier is
   *     shared through an operation scoped seal session; a customized verifier verifies every proof
   *     by itself
   */
  public TokenProofClient(ShardRouting routing, VerificationContext context) {
    this.routing = Objects.requireNonNull(routing, "routing cannot be null");
    this.context = Objects.requireNonNull(context, "context cannot be null");
  }

  /**
   * Rebuilds the token's outer-history proofs under one master seal, with the default options.
   *
   * @param token token to prepare, unchanged on every outcome
   * @return the outcome
   */
  public CompletableFuture<ConsolidationResult> consolidate(Token token) {
    return consolidate(token, ConsolidationOptions.defaults());
  }

  /**
   * Rebuilds the token's outer-history proofs under one master seal.
   *
   * <p>The future completes with a fully verified immutable token, or with an unavailable outcome
   * and a stable reason when the service cannot provide the material. Cancellation, deadline,
   * transport, server, response or integrity failures complete it exceptionally with a
   * {@link ConsolidationException}.
   *
   * @param token token to prepare, unchanged on every outcome
   * @param options deadline, cancellation and bounds
   * @return the outcome
   */
  public CompletableFuture<ConsolidationResult> consolidate(Token token,
      ConsolidationOptions options) {
    Objects.requireNonNull(token, "token cannot be null");
    Objects.requireNonNull(options, "options cannot be null");
    Run run = new Run(token, options);
    run.start();
    return run.result;
  }

  private final class Run {
    private final Token token;
    private final ConsolidationOptions options;
    private final CompletableFuture<ConsolidationResult> result = new CompletableFuture<>();
    private final OperationContext outer;
    private final Executor executor;
    private final AtomicReference<ConsolidationException> terminal = new AtomicReference<>();

    Run(Token token, ConsolidationOptions options) {
      this.token = token;
      this.options = options;
      Duration budget = options.getTimeout();
      OperationContext external = options.getOperation();
      if (external != null && external.remaining().compareTo(budget) < 0) {
        budget = external.remaining();
      }
      this.outer = OperationContext.withBudget(budget);
      this.executor = options.getExecutor() != null ? options.getExecutor()
          : ForkJoinPool.commonPool();
      if (external != null) {
        OperationContext.Registration registration = external.onCancel(this::cancelledByCaller);
        result.whenComplete((r, e) -> registration.remove());
      }
      result.whenComplete((r, e) -> {
        if (result.isCancelled()) {
          terminal.compareAndSet(null, cancelled());
        }
        try {
          outer.cancel();
        } catch (RuntimeException ex) {
          // the registered actions all ran
        }
      });
    }

    void start() {
      Deadlines.scheduleFor(this::deadline, outer.remaining().toNanos(), result);
      executor.execute(() -> {
        try {
          verifyAndPlan();
        } catch (Throwable t) {
          fail(t);
        }
      });
    }

    private ConsolidationException cancelled() {
      return new ConsolidationException(ConsolidationException.Kind.CANCELLED,
          "The attempt was cancelled.", null, null, false, null, null);
    }

    private void cancelledByCaller() {
      terminal.compareAndSet(null, cancelled());
      result.completeExceptionally(terminal.get());
    }

    private void deadline() {
      if (result.isDone()) {
        return;
      }
      terminal.compareAndSet(null, new ConsolidationException(ConsolidationException.Kind.TIMEOUT,
          "The attempt deadline expired.", null, null, true, null, null));
      result.completeExceptionally(terminal.get());
    }

    private void throwIfDone() {
      ConsolidationException known = terminal.get();
      if (known != null) {
        throw known;
      }
      if (outer.isExpired()) {
        throw new ConsolidationException(ConsolidationException.Kind.TIMEOUT,
            "The attempt deadline expired.", null, null, true, null, null);
      }
      if (outer.isCancelled() || result.isDone()) {
        throw cancelled();
      }
    }

    private void fail(Throwable raised) {
      Throwable t = raised;
      while (t instanceof CompletionException && t.getCause() != null) {
        t = t.getCause();
      }
      if (result.isDone()) {
        return;
      }
      if (t instanceof ConsolidationException) {
        result.completeExceptionally(t);
      } else {
        result.completeExceptionally(new ConsolidationException(
            ConsolidationException.Kind.INVALID_RESPONSE, "Unexpected failure: " + t,
            ConsolidationOutcome.INVALID_INPUT, null, false, null, t));
      }
    }

    private ConsolidationException integrity(String message, Throwable cause) {
      return ConsolidationException.of(ConsolidationException.Kind.INTEGRITY,
          ConsolidationOutcome.INVALID_INPUT, message, null, cause);
    }

    private void verifyAndPlan() {
      throwIfDone();
      final RootTrustBase trustBase = context.getTrustBase();
      UnicityCertificateVerifier base = context.getCertificateVerifier();
      OperationUnicityCertificateVerifier operation;
      VerificationContext tokenContext;
      if (base instanceof OperationUnicityCertificateVerifier) {
        // the caller shares one operation and chose its policy
        operation = (OperationUnicityCertificateVerifier) base;
        tokenContext = context;
      } else {
        UnicityCertificateVerifier scoped =
            base.beginOperation(VerificationPolicy.LEGACY_QUORUM, PartitionViewPolicy.none());
        if (scoped instanceof OperationUnicityCertificateVerifier) {
          operation = (OperationUnicityCertificateVerifier) scoped;
          tokenContext = context.withCertificateVerifier(operation);
        } else {
          // a customized verifier keeps verifying every proof by itself; the consolidation's own
          // checks use a stock operation
          operation = (OperationUnicityCertificateVerifier) new UnicityCertificateVerifier()
              .beginOperation(VerificationPolicy.LEGACY_QUORUM, PartitionViewPolicy.none());
          tokenContext = context;
        }
      }

      VerificationResult<VerificationStatus> original = token.verify(tokenContext);
      throwIfDone();
      if (original.getStatus() != VerificationStatus.OK) {
        throw integrity("The token does not verify.", null);
      }

      List<ConsolidationItem> items = new ArrayList<>();
      List<CertifiedTransferTransaction> transfers = token.getTransactions();
      items.add(new ConsolidationItem(
          token.getGenesis().getInclusionProof().getCertificationData(),
          token.getGenesis().getInclusionProof()));
      for (CertifiedTransferTransaction transfer : transfers) {
        items.add(new ConsolidationItem(transfer.getInclusionProof().getCertificationData(),
            transfer.getInclusionProof()));
      }
      int originalSeals = distinctSeals(items);
      long evidenceQuorumChecks = operation.sessionFor(trustBase).getStats().getQuorumChecks();

      if (originalSeals == 1) {
        InclusionProof first = items.get(0).getProof();
        result.complete(ConsolidationResult.consolidated(token, new ProofSummary(true, 1,
            evidenceQuorumChecks, first.getUnicityCertificate().getUnicitySeal()
            .getRootChainRoundNumber(), first.getUnicityCertificate().getShardTreeCertificate()
            .getShard(), originalSeals, items.size(), 0, 0,
            first.getUnicityCertificate().getUnicitySeal().getRootChainRoundNumber(),
            distinctShards(items))));
        return;
      }

      ProofConsolidator consolidator = new ProofConsolidator(routing, trustBase, operation);
      Duration left = outer.remaining();
      if (left.isZero()) {
        throw new ConsolidationException(ConsolidationException.Kind.TIMEOUT,
            "The attempt deadline expired.", null, null, true, null, null);
      }
      ConsolidationOptions attemptOptions = options.withOperation(outer)
          .withTimeout(left.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : left);
      consolidator.consolidate(items, attemptOptions).whenCompleteAsync((attempt, error) -> {
        try {
          if (error != null) {
            fail(error);
            return;
          }
          rebuild(attempt, items, tokenContext, operation, trustBase, originalSeals,
              evidenceQuorumChecks);
        } catch (Throwable t) {
          fail(t);
        }
      }, executor);
    }

    private void rebuild(ConsolidationAttempt attempt, List<ConsolidationItem> items,
        VerificationContext tokenContext, OperationUnicityCertificateVerifier operation,
        RootTrustBase trustBase, int originalSeals, long evidenceQuorumChecks) {
      throwIfDone();
      if (attempt.getStatus() == ConsolidationAttempt.Status.UNAVAILABLE) {
        result.complete(ConsolidationResult.unavailable(attempt.getUnavailability()));
        return;
      }
      ConsolidatedProofs consolidated = attempt.getProofs();

      // rebuild from the original signed bytes with only the replaceable proofs swapped, then
      // verify everything again
      List<InclusionProof> proofs = new ArrayList<>();
      for (InclusionProofResponse response : consolidated.getResponses()) {
        proofs.add(response.getInclusionProof());
      }
      Token rebuilt = Token.fromCbor(replaceOuterProofs(token.toCbor(), proofs));
      VerificationResult<VerificationStatus> verified = rebuilt.verify(tokenContext);
      throwIfDone();
      if (verified.getStatus() != VerificationStatus.OK) {
        throw integrity("The consolidated token does not verify.", null);
      }

      List<ConsolidationItem> rebuiltOuter = new ArrayList<>();
      rebuiltOuter.add(new ConsolidationItem(
          rebuilt.getGenesis().getInclusionProof().getCertificationData(),
          rebuilt.getGenesis().getInclusionProof()));
      for (CertifiedTransferTransaction transfer : rebuilt.getTransactions()) {
        rebuiltOuter.add(new ConsolidationItem(transfer.getInclusionProof().getCertificationData(),
            transfer.getInclusionProof()));
      }
      int consolidatedSeals = distinctSeals(rebuiltOuter);
      if (consolidatedSeals != 1) {
        throw integrity("The consolidated outer history does not share one seal.", null);
      }

      long totalQuorumChecks = operation.sessionFor(trustBase).getStats().getQuorumChecks();
      result.complete(ConsolidationResult.consolidated(rebuilt, new ProofSummary(false,
          consolidatedSeals, evidenceQuorumChecks, consolidated.getMasterRound(),
          consolidated.getMasterShard(), originalSeals, items.size(),
          totalQuorumChecks - evidenceQuorumChecks, consolidated.getRequests(),
          consolidated.getRequiredRound(), consolidated.getShards())));
    }
  }

  private static int distinctSeals(List<ConsolidationItem> items) {
    Set<String> seals = new HashSet<>();
    for (ConsolidationItem item : items) {
      seals.add(HexConverter.encode(
          item.getProof().getUnicityCertificate().getUnicitySeal().toCbor()));
    }
    return seals.size();
  }

  private static int distinctShards(List<ConsolidationItem> items) {
    Set<String> shards = new HashSet<>();
    for (ConsolidationItem item : items) {
      shards.add(item.getProof().getUnicityCertificate().getShardTreeCertificate().getShard()
          .toString());
    }
    return shards.size();
  }

  /**
   * Token bytes with the proofs of the outer history swapped. Every other byte (signed
   * transactions, mint justifications and their nested proofs, predicates) is copied verbatim; the
   * result is only ever used by decoding it into a token that is then fully verified.
   */
  static byte[] replaceOuterProofs(byte[] tokenBytes, List<InclusionProof> proofs) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(tokenBytes);
    List<byte[]> parts = CborDeserializer.decodeArray(tag.getData(), 3);
    List<byte[]> transferItems = CborDeserializer.decodeArray(parts.get(2));
    if (proofs.size() != transferItems.size() + 1) {
      throw ConsolidationException.of(ConsolidationException.Kind.INTEGRITY,
          ConsolidationOutcome.INVALID_INPUT, "Proof count does not match the token history.",
          null, null);
    }
    byte[][] swappedTransfers = new byte[transferItems.size()][];
    for (int i = 0; i < swappedTransfers.length; i++) {
      swappedTransfers[i] = swap(transferItems.get(i), proofs.get(i + 1));
    }
    return CborSerializer.encodeTag(
        tag.getTag(),
        CborSerializer.encodeArray(
            parts.get(0),
            swap(parts.get(1), proofs.get(0)),
            CborSerializer.encodeArray(swappedTransfers)));
  }

  private static byte[] swap(byte[] certified, InclusionProof proof) {
    List<byte[]> parts = CborDeserializer.decodeArray(certified, 2);
    return CborSerializer.encodeArray(parts.get(0), proof.toCbor());
  }
}
