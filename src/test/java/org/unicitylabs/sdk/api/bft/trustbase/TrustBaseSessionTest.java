package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NetworkId;

/** The session beyond the shared vectors: flush protocol, contention, authorities, refresh, concurrency. */
class TrustBaseSessionTest {

  private static final String NS = "test";
  private final UtbCacheFixtures fx = new UtbCacheFixtures();
  private final TrustBaseSessionOptions direct = TrustBaseSessionOptions.of(Runnable::run);

  /** A store with counters and scripted compare-and-swap behaviour over a memory store. */
  private static final class Script implements TrustBaseStore {

    final MemoryTrustBaseStore inner = new MemoryTrustBaseStore();
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger cas = new AtomicInteger();
    volatile boolean alwaysChanged;
    volatile RuntimeException casFailure;
    volatile RuntimeException readFailure;
    volatile byte[] raceOnce;
    String key;

    @Override
    public CompletableFuture<Optional<Stored>> read(String k, int maxBytes) {
      this.reads.incrementAndGet();
      if (this.readFailure != null) {
        return CompletableFuture.failedFuture(this.readFailure);
      }
      return this.inner.read(k, maxBytes);
    }

    @Override
    public CompletableFuture<CasOutcome> compareAndSwap(String k, String expected, byte[] bytes) {
      this.cas.incrementAndGet();
      if (this.alwaysChanged) {
        return CompletableFuture.completedFuture(CasOutcome.CHANGED);
      }
      if (this.casFailure != null) {
        return CompletableFuture.failedFuture(this.casFailure);
      }
      byte[] race = this.raceOnce;
      if (race != null) {
        this.raceOnce = null;
        this.inner.put(this.key, race);
      }
      return this.inner.compareAndSwap(k, expected, bytes);
    }
  }

  private static <T> T get(CompletableFuture<T> f) {
    try {
      return f.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new IllegalStateException(e.getCause());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private TrustBaseSession open(ReleasePin pin, TrustBaseStore store) {
    return get(TrustBaseSession.openPrivate(NS, pin, store, this.direct)).getSession();
  }

  private Script scriptFor(ReleasePin pin) {
    Script s = new Script();
    s.key = UtbCacheFixtures.key(pin, NS);
    return s;
  }

  private static long tip(TrustBaseSession s) {
    return s.history().getTip().get().getEpoch();
  }

  @Test
  void aFlushWritesOneCompleteBundleAndASecondFlushWritesNothing() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    TrustBaseSession s = open(pin, store);
    assertFalse(s.isDirty());
    s.append(this.fx.entry("A4"));
    assertTrue(s.isDirty());

    FlushResult first = get(s.flush());
    assertTrue(first.isSaved() && first.isWrote());
    assertFalse(s.isDirty());
    assertArrayEquals(this.fx.bundle("A1-4"), store.inner.get(store.key));
    int cas = store.cas.get();
    int reads = store.reads.get();
    FlushResult second = get(s.flush());
    assertTrue(second.isSaved() && !second.isWrote());
    assertEquals(cas, store.cas.get(), "a clean session does not write");
    assertEquals(reads, store.reads.get(), "and does not read either");
  }

  @Test
  void aStoreSuffixOfAnotherWriterIsImportedNotLost() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    TrustBaseSession s = open(pin, store);
    s.append(this.fx.entry("A4"));
    store.inner.put(store.key, this.fx.bundle("A1-5")); // a competing writer got further
    FlushResult flush = get(s.flush());
    assertTrue(flush.isSaved() && !flush.isWrote(), "the store already has everything we hold");
    assertEquals(5, tip(s), "the suffix was imported");
    assertArrayEquals(this.fx.bundle("A1-5"), store.inner.get(store.key));
  }

  @Test
  void aStaleRevisionIsRereadValidatedAndMerged() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    TrustBaseSession s = open(pin, store);
    s.append(this.fx.entry("A4"));
    store.raceOnce = this.fx.bundle("A1-3"); // changes the revision just before our first write
    FlushResult flush = get(s.flush());
    assertTrue(flush.isSaved() && flush.isWrote());
    assertEquals(2, store.cas.get(), "one lost race, one write");
    assertArrayEquals(this.fx.bundle("A1-4"), store.inner.get(store.key));
  }

  @Test
  void contentionIsRetriedThreeTimesThenPendingAndTheSessionStaysDirtyAndVerified() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    TrustBaseSession s = open(pin, store);
    s.append(this.fx.entry("A4"));
    store.alwaysChanged = true;
    FlushResult flush = get(s.flush());
    assertFalse(flush.isSaved());
    assertInstanceOf(CacheContendedException.class, flush.getError());
    assertEquals(4, store.cas.get(), "the first write and three retries");
    assertTrue(s.isDirty());
    assertEquals(4, tip(s), "verified history stays usable");
    store.alwaysChanged = false;
    assertTrue(get(s.flush()).isWrote(), "and a later flush succeeds");
  }

  @Test
  void aFailedSaveIsReportedSeparatelyAndTheDirtyStateSurvives() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    TrustBaseSession s = open(pin, store);
    s.append(this.fx.entry("A4"));
    store.casFailure = new TrustBaseStoreException(TrustBaseStoreException.Reason.QUOTA, "full");
    FlushResult flush = get(s.flush());
    assertFalse(flush.isSaved());
    assertEquals(TrustBaseStoreException.Reason.QUOTA,
        ((TrustBaseStoreException) flush.getError()).getReason());
    assertTrue(s.isDirty());
    assertEquals(4, tip(s));
  }

  @Test
  void aReadFailureCannotAuthorizeAReplacement() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    store.inner.put(store.key, new byte[] {1, 2, 3}); // damaged
    TrustBaseSession s = open(pin, store);
    store.readFailure = new TrustBaseStoreException(TrustBaseStoreException.Reason.IO, "disk");
    FlushResult flush = get(s.flush());
    assertFalse(flush.isSaved());
    assertEquals(0, store.cas.get(), "nothing was written without a successful read");
    assertArrayEquals(new byte[] {1, 2, 3}, store.inner.get(store.key));
  }

  @Test
  void aDamagedCacheIsReportedOnceAndReplacedOnlyAgainstItsExactRevision() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    store.inner.put(store.key, new byte[] {9, 9});
    TrustBaseSession.Opened opened = get(TrustBaseSession.openPrivate(NS, pin, store, this.direct));
    assertEquals(CacheRejection.MALFORMED, opened.getDiagnostics().get(0).getRejection());
    store.raceOnce = new byte[] {7, 7, 7}; // another damaged value appears before our write
    FlushResult flush = get(opened.getSession().flush());
    assertTrue(flush.isSaved() && flush.isWrote());
    long reported = flush.getDiagnostics().stream().filter(d -> d.isRejection()).count();
    assertEquals(1, reported, "reported once across the retry");
    assertArrayEquals(this.fx.bundle("A1-3"), store.inner.get(store.key));
  }

  @Test
  void anAppendLandingWhileAFlushSettlesIsNeverMarkedSaved() {
    // the store already holds exactly what the session held when the flush read its tip ...
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    TrustBaseSession s = open(pin, store);
    s.append(this.fx.entry("A4"));
    store.inner.put(store.key, this.fx.bundle("A1-4"));
    // ... and a synchronous append from another thread lands before the flush settles
    s.settleHook = () -> {
      s.settleHook = null;
      s.append(this.fx.entry("A5"));
    };
    FlushResult flush = get(s.flush());
    assertTrue(flush.isSaved() && !flush.isWrote());
    assertTrue(s.isDirty(), "the new entry is not saved yet");
    assertTrue(get(s.flush()).isWrote());
    assertArrayEquals(this.fx.bundle("A1-5"), store.inner.get(store.key));
    assertFalse(s.isDirty());
  }

  @Test
  void anAppendLandingBeforeTheWriteIsStillSavedByTheNextFlush() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    TrustBaseSession s = open(pin, store);
    s.append(this.fx.entry("A4"));
    s.settleHook = () -> {
      s.settleHook = null;
      s.append(this.fx.entry("A5"));
    };
    assertTrue(get(s.flush()).isWrote());
    get(s.flush());
    assertArrayEquals(this.fx.bundle("A1-5"), store.inner.get(store.key));
    assertFalse(s.isDirty());
  }

  @Test
  void anAuthenticatedForkLatchesAndStopsEveryWriteAndRead() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    TrustBaseSession s = open(pin, store);
    s.append(this.fx.entry("A4"));
    TrustBaseConflictException fork = assertThrows(TrustBaseConflictException.class,
        () -> s.append(this.fx.entry("F4")));
    assertTrue(fork.isAuthenticated());
    assertNotNull(s.getConflict());
    assertThrows(HistoryConflictedException.class, () -> s.history().getTip());
    assertThrows(HistoryConflictedException.class, () -> s.append(this.fx.entry("A5")));
    FlushResult flush = get(s.flush());
    assertInstanceOf(HistoryConflictedException.class, flush.getError());
    assertEquals(0, store.cas.get(), "a conflicted session never writes");
    assertNotNull(s.exportBundle(), "the export stays available");
    assertThrows(HistoryConflictedException.class,
        () -> get(s.refresh(List.of(), TrustBaseUpdateOptions.defaults())));
  }

  @Test
  void refreshFetchesThroughTheSessionAndSavesTheResult() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    TrustBaseSession s = open(pin, store);
    TrustBaseSource source = request -> {
      long from = request.getFromEpoch();
      TrustBaseEntry a4 = this.fx.entry("A4");
      TrustBaseEntry a5 = this.fx.entry("A5");
      assertEquals(4, from);
      return CompletableFuture.completedFuture(TrustBaseResponseWire.encode(
          new TrustBaseResponseWire.Fields(5, a5.entryId(), List.of(a4.encode(), a5.encode()), 4,
              request.getNetworkId().getId(), null, 0)));
    };
    assertEquals(2, get(s.refresh(List.of(source),
        TrustBaseUpdateOptions.defaults().withBackoffMs(0))));
    assertEquals(5, tip(s));
    assertFalse(s.isDirty());
    assertArrayEquals(this.fx.bundle("A1-5"), store.inner.get(store.key));
  }

  @Test
  void aFailedRefreshStillSavesTheVerifiedPrefix() {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    TrustBaseSession s = open(pin, store);
    AtomicInteger calls = new AtomicInteger();
    TrustBaseSource cut = request -> {
      if (calls.getAndIncrement() > 0) {
        return CompletableFuture.failedFuture(new java.io.IOException("cut"));
      }
      TrustBaseEntry a4 = this.fx.entry("A4");
      return CompletableFuture.completedFuture(TrustBaseResponseWire.encode(
          new TrustBaseResponseWire.Fields(6, this.fx.entry("A6").entryId(),
              List.of(a4.encode()), 4, request.getNetworkId().getId(), 5L, 0)));
    };
    assertThrows(TrustBaseUpdateException.class, () -> get(s.refresh(List.of(cut),
        TrustBaseUpdateOptions.defaults().withBackoffMs(0))));
    assertEquals(4, tip(s));
    assertArrayEquals(this.fx.bundle("A1-4"), store.inner.get(store.key), "the prefix was saved");
  }

  @Test
  void publicAuthorityNeedsARowAndAnOverrideMustExtendIt() {
    ReleasePin row = this.fx.pin("A1-3");
    NetworkId network = row.getNetworkId();
    MemoryTrustBaseStore store = new MemoryTrustBaseStore();
    TrustBaseSessionOptions withRow = this.direct.withTable(List.of(row));

    assertThrows(UnknownNetworkException.class,
        () -> get(TrustBaseSession.openPublic(network, null, store, this.direct)));

    TrustBaseSession plain = get(TrustBaseSession.openPublic(network, null, store, withRow))
        .getSession();
    assertTrue(plain.getKey().endsWith("/public"));
    assertEquals(3, tip(plain));

    TrustBaseSession longer = get(TrustBaseSession.openPublic(network, this.fx.pin("A1-5"), store,
        withRow)).getSession();
    assertEquals(5, tip(longer), "a newer pin that extends the row is used");
    assertTrue(longer.getKey().endsWith("/public"));

    // a pin of the same network that does not contain the row's checkpoint is refused
    assertThrows(TrustBasePinMismatchException.class, () -> get(TrustBaseSession.openPublic(
        network, this.fx.pin("G1-3"), store, withRow)));
  }

  @Test
  void aPrivateAuthorityNeedsItsOwnNamespace() {
    ReleasePin pin = this.fx.pin("A1-3");
    for (String bad : new String[] {"public", "", "a/b", "x".repeat(65)}) {
      assertThrows(TrustBasePinMismatchException.class, () -> get(TrustBaseSession.openPrivate(
          bad, pin, new MemoryTrustBaseStore(), this.direct)), bad);
    }
    TrustBaseSession.Opened ok = get(TrustBaseSession.openPrivate("x".repeat(64), pin,
        new MemoryTrustBaseStore(), this.direct));
    assertNotNull(ok.getSession());
  }

  @Test
  void aLocalNetworkOpensFromItsOwnBundleAloneInItsOwnNamespace() {
    byte[] bundle = this.fx.bundle("A1-3");
    MemoryTrustBaseStore store = new MemoryTrustBaseStore();
    TrustBaseSession s = get(TrustBaseSession.openLocal(bundle, store, this.direct)).getSession();
    assertTrue(s.getKey().endsWith("/local"));
    assertEquals(3, tip(s));
    ReleasePin derived = ReleasePin.fromBundle(bundle);
    assertEquals(3, derived.getPinnedEpoch());
    assertArrayEquals(this.fx.pin("A1-3").getPinnedEntryId(), derived.getPinnedEntryId());
    assertThrows(ReleasePinInvalidException.class, () -> ReleasePin.fromBundle(new byte[] {1}));
  }

  @Test
  void concurrentAppendsFlushesAndReadsOnABoundedExecutorStayConsistent() throws Exception {
    ReleasePin pin = this.fx.pin("A1-3");
    Script store = scriptFor(pin);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      TrustBaseSession s = get(TrustBaseSession.openPrivate(NS, pin, store,
          TrustBaseSessionOptions.of(pool))).getSession();
      List<CompletableFuture<?>> all = new java.util.ArrayList<>();
      for (String name : new String[] {"A4", "A5", "A6"}) {
        all.add(s.appendAsync(this.fx.entry(name)));
        all.add(s.flush());
        // readers never see a torn history
        assertTrue(s.history().getEntries().size() >= 3);
      }
      for (CompletableFuture<?> f : all) {
        f.get(10, TimeUnit.SECONDS);
      }
      assertEquals(6, tip(s));
      assertTrue(get(s.flush()).isSaved());
      assertArrayEquals(this.fx.bundle("A1-6"), store.inner.get(store.key));
    } finally {
      pool.shutdownNow();
    }
  }
}
