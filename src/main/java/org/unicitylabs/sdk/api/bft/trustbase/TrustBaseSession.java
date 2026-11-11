package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.ReleasePin.VerifiedReleasePin;

/**
 * A trust base history together with its release pin and its cache. It starts from the pinned
 * release history, merges whatever compatible cache the store holds (strictly re-verified from the
 * pinned genesis: nothing in a store is trusted for its name, age or revision), takes later entries
 * through {@link #append} and {@link #refresh}, and saves one complete bundle by compare-and-swap.
 *
 * <p>Verification reads {@link #history()}, a read-only view. An authenticated fork after the
 * release checkpoint closes the session: every later append, refresh and read of the view fails with
 * {@link HistoryConflictedException} (the live session only; an untrusted store cannot make this
 * survive deletion or a fresh process, so the application records the incident). A mismatch in the
 * pinned prefix is refused without closing it.
 *
 * <p>All mutation runs one task at a time on the executor of the options; the store futures are
 * awaited there, never on the caller's thread and never under a lock held by readers. Reads of the
 * view see a consistent snapshot of the history at any time.
 */
public final class TrustBaseSession implements TrustBaseUpdateTarget {

  /** One write and at most three retries of a changed revision. */
  public static final int MAX_CAS_ATTEMPTS = 4;

  /** A session and what was found in the cache when it opened. */
  public static final class Opened {

    private final TrustBaseSession session;
    private final List<CacheDiagnostic> diagnostics;

    Opened(TrustBaseSession session, List<CacheDiagnostic> diagnostics) {
      this.session = session;
      this.diagnostics = diagnostics;
    }

    /**
     * Get the session.
     *
     * @return the session
     */
    public TrustBaseSession getSession() {
      return this.session;
    }

    /**
     * Get what was found in the cache: a cache that is refused or unreadable leaves the embedded
     * history in place.
     *
     * @return the diagnostics
     */
    public List<CacheDiagnostic> getDiagnostics() {
      return this.diagnostics;
    }
  }

  private final Serial serial;
  private final TrustBaseStore store;
  private final String key;
  private final TrustBaseBundleLimits limits;
  private final long pinnedEpoch;
  private final ReleasePin pin;
  private volatile TrustBaseHistory history;
  /** Guards the dirty flag against the history it describes: appends and the settling of a flush. */
  private final Object lock = new Object();
  /** Test hook: runs where a flush settles the dirty flag, before it does. */
  volatile Runnable settleHook;
  private final TrustBaseHistoryView view;
  private volatile boolean dirty;
  private boolean repair;
  private CacheRejection blocked;
  private volatile TrustBaseConflictException conflict;

  private TrustBaseSession(Executor executor, TrustBaseStore store, String key,
      TrustBaseBundleLimits limits, VerifiedReleasePin verified) {
    this.serial = new Serial(executor);
    this.store = store;
    this.key = key;
    this.limits = limits;
    this.pin = verified.getPin();
    this.pinnedEpoch = this.pin.getPinnedEpoch();
    this.history = verified.getHistory();
    this.view = new View();
  }

  /**
   * Open the session of a public network: the release table row, or an override that extends it.
   *
   * @param network the network
   * @param override an explicit newer pin that extends the built-in one, or null
   * @param store the store
   * @param options the options
   * @return future with the session and what was found in the cache. Fails with
   *     {@link UnknownNetworkException} if the network has no pin,
   *     {@link ReleasePinInvalidException} if the pin or the override is invalid (never rescued by
   *     the cache) or {@link TrustBasePinMismatchException} if the override does not extend the row
   */
  public static CompletableFuture<Opened> openPublic(NetworkId network, ReleasePin override,
      TrustBaseStore store, TrustBaseSessionOptions options) {
    Objects.requireNonNull(network, "network cannot be null");
    return run(options, () -> {
      ReleasePin row = null;
      for (ReleasePin candidate : options.getTable()) {
        if (candidate.getNetworkId().equals(network)) {
          row = candidate;
          break;
        }
      }
      if (row == null) {
        throw new UnknownNetworkException("No release pin for network " + network.getId() + ".");
      }
      VerifiedReleasePin builtin = row.verify(options.getLimits());
      VerifiedReleasePin selected = builtin;
      if (override != null) {
        if (!override.getNetworkId().equals(network)) {
          throw new TrustBasePinMismatchException("The override is of another network.");
        }
        VerifiedReleasePin replacement = override.verify(options.getLimits());
        if (!builtin.isExtendedBy(replacement)) {
          throw new TrustBasePinMismatchException(
              "The override does not extend the release pin of the network.");
        }
        selected = replacement;
      }
      return open(selected, TrustBaseCacheKey.PUBLIC_NAMESPACE, store, options);
    });
  }

  /**
   * Open the session of a private or test network under an explicit authority: its own pin and a
   * namespace of its own. This chooses another authority; it does not switch any check off.
   *
   * @param namespace the trust namespace, 1 to 64 bytes, no {@code /}, and not {@code public}
   * @param pin the pin, with the full embedded bundle
   * @param store the store
   * @param options the options
   * @return future with the session and what was found in the cache. Fails with
   *     {@link TrustBasePinMismatchException} for a namespace that is not acceptable and
   *     {@link ReleasePinInvalidException} if the pin is invalid
   */
  public static CompletableFuture<Opened> openPrivate(String namespace, ReleasePin pin,
      TrustBaseStore store, TrustBaseSessionOptions options) {
    Objects.requireNonNull(namespace, "namespace cannot be null");
    Objects.requireNonNull(pin, "pin cannot be null");
    return run(options, () -> {
      if (!TrustBaseCacheKey.isValidPrivateNamespace(namespace)) {
        throw new TrustBasePinMismatchException("A private authority needs its own namespace.");
      }
      return open(pin.verify(options.getLimits()), namespace, store, options);
    });
  }

  /**
   * Open the session of a local test network from its own complete bundle, in the fixed namespace
   * {@code local}, never shared with {@code public}.
   *
   * @param bundle the deployment's own complete bundle
   * @param store the store
   * @param options the options
   * @return future with the session; see {@link #openPrivate}
   */
  public static CompletableFuture<Opened> openLocal(byte[] bundle, TrustBaseStore store,
      TrustBaseSessionOptions options) {
    Objects.requireNonNull(bundle, "bundle cannot be null");
    return run(options, () -> open(ReleasePin.fromBundle(bundle).verify(options.getLimits()),
        TrustBaseCacheKey.LOCAL_NAMESPACE, store, options));
  }

  private static <T> CompletableFuture<T> run(TrustBaseSessionOptions options,
      Supplier<T> task) {
    Objects.requireNonNull(options, "options cannot be null");
    CompletableFuture<T> future = new CompletableFuture<>();
    options.getExecutor().execute(() -> {
      try {
        future.complete(task.get());
      } catch (Throwable e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  private static Opened open(VerifiedReleasePin verified, String namespace, TrustBaseStore store,
      TrustBaseSessionOptions options) {
    Objects.requireNonNull(store, "store cannot be null");
    ReleasePin pin = verified.getPin();
    String key = TrustBaseCacheKey.of(pin.getNetworkId(), pin.getGenesisEntryId(), namespace);
    TrustBaseSession session = new TrustBaseSession(options.getExecutor(), store, key,
        options.getLimits(), verified);
    List<CacheDiagnostic> diagnostics = new ArrayList<>();
    session.load(diagnostics);
    return new Opened(session, diagnostics);
  }

  /** Read the cache and merge it into the embedded history. Nothing is written. */
  private void load(List<CacheDiagnostic> diagnostics) {
    Optional<TrustBaseStore.Stored> stored;
    try {
      stored = await(this.store.read(this.key, this.limits.getMaxBytes()));
    } catch (TrustBaseStoreException e) {
      if (e.getReason() == TrustBaseStoreException.Reason.OVERSIZE) {
        diagnostics.add(CacheDiagnostic.rejected(CacheRejection.OVERSIZE));
        this.blocked = CacheRejection.OVERSIZE;
      } else {
        diagnostics.add(CacheDiagnostic.unavailable(e));
      }
      return;
    } catch (RuntimeException e) {
      diagnostics.add(CacheDiagnostic.unavailable(e));
      return;
    }
    if (!stored.isPresent()) {
      return;
    }
    Loaded loaded = loadCache(stored.get().getBytes());
    if (loaded.rejection != null) {
      diagnostics.add(CacheDiagnostic.rejected(loaded.rejection));
      if (loaded.rejection == CacheRejection.OVERSIZE
          || loaded.rejection == CacheRejection.PIN_MISMATCH) {
        this.blocked = loaded.rejection;
      } else {
        this.repair = true;
      }
      return;
    }
    TrustBaseHistory cache = loaded.history;
    long embeddedTip = this.history.getTip().get().getEpoch();
    long cacheTip = cache.getTip().get().getEpoch();
    if (Long.compareUnsigned(cacheTip, embeddedTip) > 0) {
      // A longer compatible cache extends the embedded history: its entries past the embedded tip
      // are appended, so the embedded witnesses (the first held) stay and an older SDK never
      // truncates the cache.
      try {
        for (TrustBaseEntry entry : cache.getEntries()) {
          if (Long.compareUnsigned(entry.getEpoch(), embeddedTip) > 0) {
            this.history.append(entry);
          }
        }
      } catch (TrustBaseException e) {
        // Verified in the cache's own chain, which agrees with the checkpoint, so the append only
        // extends. Should it ever not, the embedded history stands and the cache is reported.
        this.history = this.pin.verify(this.limits).getHistory();
        this.repair = true;
        diagnostics.add(CacheDiagnostic.rejected(CacheRejection.MALFORMED));
      }
    } else {
      // a shorter prefix is extended from the embedded bundle
      this.dirty = Long.compareUnsigned(cacheTip, embeddedTip) < 0;
    }
  }

  private static final class Loaded {
    final TrustBaseHistory history;
    final CacheRejection rejection;

    Loaded(TrustBaseHistory history, CacheRejection rejection) {
      this.history = history;
      this.rejection = rejection;
    }

    static Loaded ok(TrustBaseHistory history) {
      return new Loaded(history, null);
    }

    static Loaded reject(CacheRejection rejection) {
      return new Loaded(null, rejection);
    }
  }

  /**
   * Strictly load cache bytes from the pinned genesis, then check them against the checkpoint.
   * Staged: nothing is published here. Budgets are judged as budgets, before any decoding or
   * signature work.
   */
  private Loaded loadCache(byte[] bytes) {
    if (bytes.length > this.limits.getMaxBytes()) {
      return Loaded.reject(CacheRejection.OVERSIZE);
    }
    TrustBaseBundle bundle;
    try {
      TrustBaseResponseWire.Fields header =
          TrustBaseResponseWire.parse(bytes, Integer.MAX_VALUE);
      if (header.getEntries().size() > this.limits.getMaxEntries()) {
        return Loaded.reject(CacheRejection.OVERSIZE);
      }
      for (byte[] entry : header.getEntries()) {
        if (entry.length > this.limits.getMaxEntryBytes()) {
          return Loaded.reject(CacheRejection.OVERSIZE);
        }
      }
      if (header.getNetwork() != this.pin.getNetworkId().getId()) {
        return Loaded.reject(CacheRejection.WRONG_NETWORK);
      }
      bundle = TrustBaseBundle.decode(bytes, this.limits);
    } catch (TrustBaseException e) {
      return Loaded.reject(CacheRejection.MALFORMED);
    }
    if (!bundle.getNetworkId().equals(this.pin.getNetworkId())) {
      return Loaded.reject(CacheRejection.WRONG_NETWORK);
    }
    List<TrustBaseEntry> entries = bundle.getEntries();
    if (!Arrays.equals(entries.get(0).entryId(), this.pin.getGenesisEntryId())) {
      return Loaded.reject(CacheRejection.WRONG_GENESIS);
    }
    TrustBaseHistory staged = TrustBaseHistory.create(this.pin.getGenesisEntryId());
    try {
      for (TrustBaseEntry entry : entries) {
        staged.append(entry);
      }
    } catch (TrustBaseException e) {
      return Loaded.reject(CacheRejection.MALFORMED);
    }
    // agree with the checkpoint over the overlap, and reach it when longer
    long cacheTip = staged.getTip().get().getEpoch();
    long overlap = Long.compareUnsigned(cacheTip, this.pinnedEpoch) < 0 ? cacheTip
        : this.pinnedEpoch;
    Optional<TrustBaseEntry> a = staged.entryForEpoch(overlap);
    Optional<TrustBaseEntry> b = this.history.entryForEpoch(overlap);
    if (!a.isPresent() || !b.isPresent() || !Arrays.equals(a.get().entryId(), b.get().entryId())) {
      return Loaded.reject(CacheRejection.PIN_MISMATCH);
    }
    return Loaded.ok(staged);
  }

  private static <T> T await(CompletableFuture<T> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CompletionException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new CompletionException(cause);
    }
  }

  // ---------------------------------------------------------------- the update target

  @Override
  public Optional<NetworkId> getNetworkId() {
    return this.history.getNetworkId();
  }

  @Override
  public Optional<TrustBaseEntry> getTip() {
    return this.history.getTip();
  }

  /**
   * Add one entry, authenticated from the held entry before it. The calling thread runs the work: use
   * {@link #appendAsync} to serialize with a flush in flight.
   *
   * @param entry the entry
   * @return whether it was added or already held
   * @throws HistoryConflictedException if the session is closed by a fork
   * @throws TrustBaseConflictException if this entry is an authenticated fork after the checkpoint;
   *     the session is now closed
   * @throws TrustBasePinMismatchException if it differs from the pinned prefix
   * @throws TrustBaseException any other refusal of {@link TrustBaseHistory#append}: nothing latches
   */
  @Override
  public TrustBaseAppendOutcome append(TrustBaseEntry entry) {
    synchronized (this.lock) {
      return appendNow(entry);
    }
  }

  /**
   * Add one entry on the session's executor, after every task already queued.
   *
   * @param entry the entry
   * @return future with the outcome; fails as {@link #append} throws
   */
  public CompletableFuture<TrustBaseAppendOutcome> appendAsync(TrustBaseEntry entry) {
    return this.serial.submit(() -> append(entry));
  }

  private TrustBaseAppendOutcome appendNow(TrustBaseEntry entry) {
    Objects.requireNonNull(entry, "entry cannot be null");
    throwIfConflicted();
    Optional<NetworkId> network = this.history.getNetworkId();
    if (network.isPresent() && !network.get().equals(entry.getNetworkId())) {
      throw new TrustBaseNetworkException("Network " + entry.getNetworkId().getId()
          + ", the history is of " + network.get().getId() + ".");
    }
    TrustBaseAppendOutcome outcome;
    try {
      outcome = this.history.append(entry);
    } catch (TrustBaseConflictException e) {
      throw classifyConflict(entry, e);
    }
    if (outcome == TrustBaseAppendOutcome.APPENDED) {
      this.dirty = true;
    }
    return outcome;
  }

  /**
   * A different entry for a held epoch is not yet a fork: the pinned prefix is settled by the
   * checkpoint, and after it the offered entry has to verify against the held predecessor before it
   * counts as evidence. Only then the session closes.
   */
  private RuntimeException classifyConflict(TrustBaseEntry entry, TrustBaseConflictException error) {
    boolean pinned = Long.compareUnsigned(entry.getEpoch(), this.pinnedEpoch) <= 0;
    Optional<TrustBaseEntry> predecessor = this.history.entryForEpoch(entry.getEpoch() - 1);
    Optional<TrustBaseEntry> held = this.history.entryForEpoch(entry.getEpoch());
    if (!predecessor.isPresent() || !held.isPresent()) {
      // The genesis epoch has no predecessor to verify against: the checkpoint settles it.
      return pinned ? new TrustBasePinMismatchException("Epoch "
          + Long.toUnsignedString(entry.getEpoch()) + " differs from the pinned history.") : error;
    }
    try {
      entry.verifySuccessor(predecessor.get());
    } catch (TrustBaseException refusal) {
      return refusal;
    }
    if (pinned) {
      return new TrustBasePinMismatchException("Epoch " + Long.toUnsignedString(entry.getEpoch())
          + " differs from the pinned history.");
    }
    TrustBaseConflictException fork = new TrustBaseConflictException(entry.getEpoch(),
        new TrustBaseConflictDetails(true, entry.getEpoch(), held.get().encode(),
            held.get().entryId(), entry.getNetworkId().getId(), entry.encode(), entry.entryId()));
    this.conflict = fork;
    return fork;
  }

  private void throwIfConflicted() {
    TrustBaseConflictException fork = this.conflict;
    if (fork != null) {
      throw new HistoryConflictedException(fork);
    }
  }

  // ---------------------------------------------------------------- reading

  /**
   * Get the read-only view that verification uses. Every read fails with
   * {@link HistoryConflictedException} once an authenticated fork closed the session.
   *
   * @return the view
   */
  public TrustBaseHistoryView history() {
    return this.view;
  }

  /**
   * Export the held history as one complete bundle. Available on a conflicted session too.
   *
   * @return the tag-39015 bundle
   */
  public byte[] exportBundle() {
    return this.history.toBundle();
  }

  /**
   * Get the authenticated fork that closed the session, with its evidence.
   *
   * @return the conflict, or null
   */
  public TrustBaseConflictException getConflict() {
    return this.conflict;
  }

  /**
   * Tell whether entries are held that the store does not have yet (a failed or not yet attempted
   * save).
   *
   * @return true if dirty
   */
  public boolean isDirty() {
    return this.dirty;
  }

  /**
   * Get the store key.
   *
   * @return the key
   */
  public String getKey() {
    return this.key;
  }

  private final class View implements TrustBaseHistoryView {

    private TrustBaseHistory live() {
      throwIfConflicted();
      return TrustBaseSession.this.history;
    }

    @Override
    public Optional<NetworkId> getNetworkId() {
      return live().getNetworkId();
    }

    @Override
    public Optional<TrustBaseEntry> getTip() {
      return live().getTip();
    }

    @Override
    public List<TrustBaseEntry> getEntries() {
      return live().getEntries();
    }

    @Override
    public Optional<TrustBaseEntry> entryForEpoch(long epoch) {
      return live().entryForEpoch(epoch);
    }

    @Override
    public OptionalLong epochEndRound(long epoch) {
      return live().epochEndRound(epoch);
    }

    @Override
    public byte[] toBundle() {
      return live().toBundle();
    }

    @Override
    public byte[] getGenesisPin() {
      return live().getGenesisPin();
    }
  }

  // ---------------------------------------------------------------- saving and refreshing

  /**
   * Save the held history if it is not saved yet. Writes one complete bundle by compare-and-swap:
   * the stored bytes are reread and fully validated, a compatible longer suffix is imported, and a
   * changed revision is retried at most {@link #MAX_CAS_ATTEMPTS} writes in all.
   *
   * <p>A clean session (nothing unsaved, no damaged or blocked cache to deal with) flushes without
   * reading the store: {@code saved} then means nothing held here is unsaved, not that the store
   * matches. A suffix another writer saved, or a fork in the store, is found when the session is
   * opened again or when something here is saved. A damaged cache (or another network or genesis) is
   * replaced only against its exact observed revision and reported in the result. A valid history
   * over the budget, or an authenticated chain diverging at or before the checkpoint, is never
   * overwritten.
   *
   * @return future with the result: saved (with the damaged cache it replaced, if any, and whether
   *     it wrote) or pending with the reason; the history stays dirty and verified and the
   *     application may retry
   */
  public CompletableFuture<FlushResult> flush() {
    return this.serial.submit(this::flushNow);
  }

  private FlushResult flushNow() {
    if (this.conflict != null) {
      return FlushResult.pending(new HistoryConflictedException(this.conflict));
    }
    if (!this.dirty && !this.repair && this.blocked == null) {
      // Nothing of ours to save, nothing to repair, nothing blocked: a clean flush does not touch
      // the store (a reread is a full strict re-verification).
      return FlushResult.saved(false, new ArrayList<>());
    }
    List<CacheDiagnostic> diagnostics = new ArrayList<>();
    for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
      Optional<TrustBaseStore.Stored> read;
      try {
        read = await(this.store.read(this.key, this.limits.getMaxBytes()));
      } catch (RuntimeException e) {
        return FlushResult.pending(e);
      }
      String expected = null;
      byte[] diskTip = null;
      if (read.isPresent()) {
        expected = read.get().getRevision();
        Loaded loaded = loadCache(read.get().getBytes());
        if (loaded.rejection == null) {
          // import what the store holds past the checkpoint; a fork latches
          for (TrustBaseEntry entry : loaded.history.getEntries()) {
            if (Long.compareUnsigned(entry.getEpoch(), this.pinnedEpoch) > 0) {
              try {
                append(entry);
              } catch (HistoryConflictedException | TrustBaseConflictException e) {
                return FlushResult.pending(e instanceof HistoryConflictedException ? e
                    : new HistoryConflictedException((TrustBaseConflictException) e));
              } catch (TrustBaseException e) {
                return FlushResult.pending(new TrustBasePinMismatchException(
                    "The stored history disagrees with the checkpoint."));
              }
            }
          }
          this.blocked = null;
          diskTip = loaded.history.getTip().get().entryId();
        } else if (loaded.rejection == CacheRejection.OVERSIZE
            || loaded.rejection == CacheRejection.PIN_MISMATCH) {
          // a valid history over the budget, or an authenticated chain that diverges from the
          // checkpoint, is evidence or a newer cache: never overwritten
          this.blocked = loaded.rejection;
          return FlushResult.pending(new CacheRejectedException(loaded.rejection));
        } else {
          // damaged, or another network or genesis: replaced against exactly this revision, and
          // reported in the result
          this.blocked = null;
          this.repair = true;
          CacheDiagnostic diagnostic = CacheDiagnostic.rejected(loaded.rejection);
          if (!diagnostics.contains(diagnostic)) {
            diagnostics.add(diagnostic);
          }
        }
      } else {
        this.blocked = null;
      }
      byte[] ours = this.history.getTip().get().entryId();
      Runnable hook = this.settleHook;
      if (hook != null) {
        hook.run();
      }
      if ((diskTip != null && Arrays.equals(diskTip, ours)) || (expected == null && !this.dirty)) {
        // the store holds this history (other valid witnesses are not worth a rewrite), or there
        // is nothing of ours to save
        this.repair = false;
        settle(ours);
        return FlushResult.saved(false, diagnostics);
      }
      byte[] out = this.history.toBundle();
      try {
        if (await(this.store.compareAndSwap(this.key, expected, out))
            == TrustBaseStore.CasOutcome.WRITTEN) {
          // an append during the save keeps the history dirty
          settle(ours);
          this.repair = false;
          return FlushResult.saved(true, diagnostics);
        }
      } catch (RuntimeException e) {
        return FlushResult.pending(e);
      }
    }
    return FlushResult.pending(
        new CacheContendedException("The store changed during every save attempt."));
  }

  /**
   * The store holds the history as it was at {@code saved} (its tip): the session is clean only if
   * nothing was appended since, which a synchronous append from another thread may have done.
   */
  private void settle(byte[] saved) {
    synchronized (this.lock) {
      this.dirty = !Arrays.equals(this.history.getTip().get().entryId(), saved);
    }
  }

  /**
   * Fetch the entries after the held tip from the sources (see {@link TrustBaseUpdater}) and save
   * the result.
   *
   * @param sources sources, tried in order
   * @param options update options
   * @return future with the entries appended; the save outcome is {@link #flush}'s. Fails with
   *     {@link HistoryConflictedException} if the session is closed by a fork,
   *     {@link TrustBaseConflictException} if a source served an authenticated fork and
   *     {@link TrustBaseUpdateException} if no source served the range (a valid prefix is still
   *     saved)
   */
  public CompletableFuture<Integer> refresh(List<? extends TrustBaseSource> sources,
      TrustBaseUpdateOptions options) {
    return this.serial.submit(() -> {
      throwIfConflicted();
      int appended;
      try {
        appended = TrustBaseUpdater.update(this, sources, options);
      } catch (RuntimeException e) {
        // A valid prefix stays held; saving it is still worth a try.
        flushNow();
        throw e;
      }
      flushNow();
      return appended;
    });
  }

  /** Runs submitted tasks one at a time, in order, on the executor. */
  private static final class Serial {

    private final Executor executor;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean running;

    Serial(Executor executor) {
      this.executor = executor;
    }

    <T> CompletableFuture<T> submit(Supplier<T> task) {
      CompletableFuture<T> future = new CompletableFuture<>();
      Runnable job = () -> {
        try {
          future.complete(task.get());
        } catch (Throwable e) {
          future.completeExceptionally(e);
        }
      };
      synchronized (this) {
        this.queue.add(job);
        if (this.running) {
          return future;
        }
        this.running = true;
      }
      this.executor.execute(this::drain);
      return future;
    }

    private void drain() {
      for (;;) {
        Runnable job;
        synchronized (this) {
          job = this.queue.poll();
          if (job == null) {
            this.running = false;
            return;
          }
        }
        job.run();
      }
    }
  }
}
