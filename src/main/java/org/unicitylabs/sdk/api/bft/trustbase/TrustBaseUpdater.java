package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseUpdateException.Cause;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseUpdateException.CauseKind;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseUpdateException.Failure;

/**
 * Catches a trust base up from untrusted sources. It follows bft-core {@code gettrustbase.Sync}
 * and the JS and Rust SDKs: the entries after the held tip are fetched page by page and each is
 * appended through the target's {@code append}, which authenticates it from the entry before it.
 * The first page of a paged range freezes the end epoch and its entry id and every later page
 * echoes them, so a source that lags or is on another history is skipped and the next source
 * continues from the same cursor. An authenticated prefix is kept even when a later page fails.
 *
 * <p>Sources are untrusted. A source's tip is unauthenticated metadata: a lagging or hostile source
 * cannot end an update as "current"; only when every source says there is nothing newer is the
 * target taken as current, and that is a result (0 appended), not an error, and nothing in it is
 * authenticated. Busy and unavailable are asked again on the next pass, other refusals are final for
 * the call.
 *
 * <p>A call does not share its work with a concurrent call on the same target: the caller (the
 * session, for a cache) serializes updates. The calls are blocking; {@link #updateAsync} runs one
 * on a caller-supplied executor and cancelling its future cancels the request in flight and the
 * pause between passes.
 */
public final class TrustBaseUpdater {

  private TrustBaseUpdater() {
  }

  /**
   * Fetch and append the entries after the held tip, blocking the calling thread.
   *
   * @param target what to extend
   * @param sources sources, tried in order
   * @param options options
   * @return the number of entries appended. 0 means the target is current as far as every source
   *     says (all answered "nothing newer"); that is a result, not an error, and nothing in it is
   *     authenticated
   * @throws TrustBaseUpdateException if no source served the range, there is no source or no
   *     network, or the update was cancelled; the entries appended before are held
   * @throws TrustBaseConflictException if the target reports an authenticated fork, which is for
   *     the application and not for the next source
   */
  public static int update(TrustBaseUpdateTarget target, List<? extends TrustBaseSource> sources,
      TrustBaseUpdateOptions options) {
    return new Run(target, sources, options).run();
  }

  /**
   * Run {@link #update} on an executor.
   *
   * @param target what to extend
   * @param sources sources, tried in order
   * @param options options
   * @param executor where the blocking update runs; not the UI thread, not the common pool
   * @return future with the number of entries appended. Cancelling it cancels the update
   */
  public static CompletableFuture<Integer> updateAsync(TrustBaseUpdateTarget target,
      List<? extends TrustBaseSource> sources, TrustBaseUpdateOptions options,
      Executor executor) {
    Objects.requireNonNull(executor, "executor cannot be null");
    Run run = new Run(target, sources, options);
    CompletableFuture<Integer> future = new CompletableFuture<>();
    future.whenComplete((result, error) -> {
      if (future.isCancelled()) {
        run.cancel();
      }
    });
    executor.execute(() -> {
      try {
        future.complete(run.run());
      } catch (Throwable e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  /**
   * Verify, and when the only thing missing is a trust base entry, update the target from the
   * sources and verify once more.
   *
   * <p>{@code verify} is run first. A result that is not the typed no-entry failure is returned
   * as it is: no fetch can help it. On the typed no-entry failure the target is updated (see
   * {@link #update}); if that appended nothing, the first result stands, and otherwise
   * {@code verify} runs once more and its result is returned, whatever it is. There is no loop:
   * one update, one retry.
   *
   * @param target what {@code verify} reads; it is extended in place
   * @param sources where newer entries come from
   * @param options update options
   * @param verify the verification, run against the target
   * @param isMissingEntry whether a result is the typed no-entry failure
   * @param <R> the result type of the verification
   * @return the result of the last verification
   * @throws TrustBaseUpdateException if the update failed (as opposed to finding nothing newer)
   *     and the entries it did append do not make the verification possible
   */
  public static <R> R verifyWithUpdate(TrustBaseUpdateTarget target,
      List<? extends TrustBaseSource> sources, TrustBaseUpdateOptions options, Supplier<R> verify,
      Predicate<R> isMissingEntry) {
    Objects.requireNonNull(verify, "verify cannot be null");
    Objects.requireNonNull(isMissingEntry, "isMissingEntry cannot be null");
    R first = verify.get();
    if (!isMissingEntry.test(first)) {
      return first;
    }
    int appended;
    try {
      appended = update(target, sources, options);
    } catch (TrustBaseUpdateException e) {
      // A later page may have failed after the entry that was needed arrived: the prefix is held,
      // so verify again, and fail only if it still lacks the entry.
      if (e.getAppended() > 0) {
        R retried = verify.get();
        if (!isMissingEntry.test(retried)) {
          return retried;
        }
      }
      throw e;
    }
    return appended == 0 ? first : verify.get();
  }

  /** One update call: the cursor, the snapshot and the cancellation belong to it. */
  private static final class Run {

    private final TrustBaseUpdateTarget target;
    private final List<? extends TrustBaseSource> sources;
    private final TrustBaseUpdateOptions options;
    private final CountDownLatch cancelled = new CountDownLatch(1);
    private final AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();

    Run(TrustBaseUpdateTarget target, List<? extends TrustBaseSource> sources,
        TrustBaseUpdateOptions options) {
      this.target = Objects.requireNonNull(target, "target cannot be null");
      this.sources = List.copyOf(Objects.requireNonNull(sources, "sources cannot be null"));
      this.options = Objects.requireNonNull(options, "options cannot be null");
    }

    void cancel() {
      this.cancelled.countDown();
      CompletableFuture<?> current = this.inFlight.get();
      if (current != null) {
        current.cancel(true);
      }
    }

    private boolean isCancelled() {
      return this.cancelled.getCount() == 0;
    }

    private long nextFrom() {
      Optional<TrustBaseEntry> tip = this.target.getTip();
      if (!tip.isPresent()) {
        return this.options.getGenesisEpoch();
      }
      long epoch = tip.get().getEpoch();
      return epoch == -1L ? epoch : epoch + 1; // saturate at 2^64 - 1
    }

    int run() {
      List<Cause> causes = new ArrayList<>();
      int appended = 0;
      if (this.sources.isEmpty()) {
        throw new TrustBaseUpdateException(Failure.NO_SOURCE, causes, appended);
      }
      NetworkId network = this.target.getNetworkId().orElse(this.options.getNetwork());
      if (network == null) {
        throw new TrustBaseUpdateException(Failure.NO_NETWORK, causes, appended);
      }
      TrustBaseRangeLimits limits = this.options.getLimits();
      boolean[] finallyRefused = new boolean[this.sources.size()];
      // The cursor and the frozen snapshot belong to the whole call, not to a source: the next
      // source continues where the last stopped, and a source that only knows the older tip
      // cannot end the call as "current" once any source announced a higher end. Both reset when
      // an entry is refused.
      long from = nextFrom();
      TrustBaseRequest.Snapshot snapshot = null;

      for (int round = 0; round < this.options.getRounds(); round++) {
        if (round > 0 && this.pause(this.options.getBackoffMs())) {
          throw new TrustBaseUpdateException(Failure.CANCELLED, causes, appended);
        }
        int current = 0;
        for (int index = 0; index < this.sources.size(); index++) {
          if (finallyRefused[index]) {
            continue;
          }
          TrustBaseSource source = this.sources.get(index);
          for (;;) {
            if (this.isCancelled()) {
              throw new TrustBaseUpdateException(Failure.CANCELLED, causes, appended);
            }
            // The target moved on by other means (a concurrent append): the frozen range no
            // longer applies.
            if (Long.compareUnsigned(this.nextFrom(), from) > 0) {
              from = this.nextFrom();
              snapshot = null;
            }
            TrustBaseRequest request =
                new TrustBaseRequest(from, limits.getMaxEntries(), network, snapshot);

            byte[] bytes;
            try {
              bytes = this.fetch(source, request);
            } catch (CancellationException e) {
              if (this.isCancelled()) {
                throw new TrustBaseUpdateException(Failure.CANCELLED, causes, appended);
              }
              causes.add(new Cause(index, CauseKind.SOURCE, e, null));
              break;
            } catch (Exception e) {
              causes.add(new Cause(index, CauseKind.SOURCE, e, null));
              break;
            }
            TrustBaseRange page;
            try {
              page = TrustBaseRange.decode(bytes, limits, network);
            } catch (TrustBaseException e) {
              causes.add(new Cause(index, CauseKind.PAGE, e, null));
              break;
            }

            // A source's tip is unauthenticated metadata: a lagging or hostile source must not
            // end the update. Only when every source says there is nothing newer is the target
            // taken as current.
            if (page.getStatus() == TrustBaseStatus.NOT_YET_AVAILABLE && snapshot == null
                && Long.compareUnsigned(page.getEffectiveEnd(), from) < 0) {
              current++;
              break;
            }
            // A source that refuses the echoed snapshot as another history may be the honest
            // one: the snapshot came from whichever source answered the first page, and a
            // hostile first page can announce a fake end. Drop it, start again from the tip and
            // ask the same source once more without it; a mismatch without a snapshot is final,
            // so this terminates.
            if (page.getStatus() == TrustBaseStatus.HISTORY_MISMATCH && snapshot != null) {
              causes.add(new Cause(index, CauseKind.STATUS, null, page.getStatus()));
              snapshot = null;
              from = this.nextFrom();
              continue;
            }
            if (page.getStatus() != TrustBaseStatus.OK) {
              causes.add(new Cause(index, CauseKind.STATUS, null, page.getStatus()));
              // Busy and unavailable may pass; the other refusals will not.
              if (page.getStatus() != TrustBaseStatus.BUSY
                  && page.getStatus() != TrustBaseStatus.UNAVAILABLE) {
                finallyRefused[index] = true;
              }
              break;
            }
            if (page.getFromEpoch() != from
                || (snapshot != null && (page.getEffectiveEnd() != snapshot.getEnd()
                    || page.getEndEntryId() == null
                    || !Arrays.equals(page.getEndEntryId(), snapshot.getEntryId())))) {
              causes.add(new Cause(index, CauseKind.NOT_CONTINUING, null, null));
              break;
            }

            boolean refused = false;
            for (TrustBaseEntry entry : page.getEntries()) {
              try {
                if (this.target.append(entry) == TrustBaseAppendOutcome.APPENDED) {
                  appended++;
                }
              } catch (TrustBaseConflictException e) {
                if (e.isAuthenticated()) {
                  // Not a refusal of this source: an authenticated fork is for the application,
                  // not for the next source.
                  throw e;
                }
                causes.add(new Cause(index, CauseKind.REFUSED, e, null));
                refused = true;
                snapshot = null;
                from = this.nextFrom();
                break;
              } catch (TrustBaseException e) {
                // The source served an entry the target refuses: keep the authenticated prefix,
                // drop the snapshot, try the next source.
                causes.add(new Cause(index, CauseKind.REFUSED, e, null));
                refused = true;
                snapshot = null;
                from = this.nextFrom();
                break;
              }
            }
            if (refused) {
              break;
            }
            if (page.getNextEpoch() == null) {
              return appended;
            }
            if (snapshot == null) {
              byte[] end = page.getEndEntryId();
              snapshot = new TrustBaseRequest.Snapshot(page.getEffectiveEnd(),
                  end == null ? new byte[TrustBaseEntry.ID_LENGTH] : end);
            }
            from = page.getNextEpoch();
          }
        }
        if (current == this.sources.size()) {
          return appended;
        }
      }
      throw new TrustBaseUpdateException(Failure.NONE_SERVED, causes, appended);
    }

    private byte[] fetch(TrustBaseSource source, TrustBaseRequest request) throws Exception {
      CompletableFuture<byte[]> future = source.getTrustBasePage(request);
      if (future == null) {
        throw new IllegalStateException("The source returned no future.");
      }
      this.inFlight.set(future);
      try {
        if (this.isCancelled()) {
          future.cancel(true);
        }
        return future.get();
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof CancellationException) {
          throw (CancellationException) cause;
        }
        throw cause instanceof Exception ? (Exception) cause : e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        future.cancel(true);
        this.cancelled.countDown();
        throw new CancellationException("Interrupted.");
      } finally {
        this.inFlight.set(null);
      }
    }

    /** Wait for the pause between passes. Returns true when cancelled during it. */
    private boolean pause(long ms) {
      try {
        return this.cancelled.await(ms, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        this.cancelled.countDown();
        return true;
      }
    }
  }
}
