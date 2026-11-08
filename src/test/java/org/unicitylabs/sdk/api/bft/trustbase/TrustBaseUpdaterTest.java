package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseUpdateException.CauseKind;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseUpdateException.Failure;

/** The fetch loop over scripted untrusted sources. */
class TrustBaseUpdaterTest {

  private static final int NETWORK = 5;
  private static final NetworkId NET = NetworkId.fromId((short) NETWORK);
  private static final TrustBaseTestChain.Committee COMMITTEE =
      new TrustBaseTestChain.Committee(4, 1, 7);
  private static final TrustBaseUpdateOptions FAST =
      TrustBaseUpdateOptions.defaults().withBackoffMs(0);

  /** Epochs 1..6: entry(1) is the genesis. */
  private static final List<TrustBaseEntry> CHAIN = chain(6);

  private static List<TrustBaseEntry> chain(int epochs) {
    List<TrustBaseEntry> entries = new ArrayList<>();
    entries.add(TrustBaseTestChain.genesis(NETWORK, 1, 1, COMMITTEE));
    for (int e = 2; e <= epochs; e++) {
      entries.add(TrustBaseTestChain.successor(entries.get(e - 2), COMMITTEE, COMMITTEE,
          100L * e, TrustBaseTestChain.firstN(COMMITTEE, 3)));
    }
    return entries;
  }

  private static TrustBaseEntry entry(int epoch) {
    return CHAIN.get(epoch - 1);
  }

  private static TrustBaseHistory genesisOnly() {
    TrustBaseHistory history = TrustBaseHistory.create(entry(1).entryId());
    history.append(entry(1));
    return history;
  }

  /** The OK page of epochs {@code first..last} of the chain, ending at {@code end}. */
  private static byte[] page(int first, int last, int end, Long next) {
    List<byte[]> raw = new ArrayList<>();
    for (int e = first; e <= last; e++) {
      raw.add(entry(e).encode());
    }
    return pageRaw(first, end, entry(end).entryId(), raw, next);
  }

  private static byte[] pageRaw(long from, long end, byte[] endId, List<byte[]> raw, Long next) {
    return TrustBaseResponseWire.encode(
        new TrustBaseResponseWire.Fields(end, endId, raw, from, NETWORK, next, 0));
  }

  private static byte[] status(TrustBaseStatus status, long from, long end) {
    return TrustBaseResponseWire.encode(new TrustBaseResponseWire.Fields(end, null,
        Collections.emptyList(), from, NETWORK, null, status.wire()));
  }

  /** A scripted source: answers by (call number, request). */
  private static final class Script implements TrustBaseSource {

    final List<TrustBaseRequest> requests = new CopyOnWriteArrayList<>();
    private final BiFunction<Integer, TrustBaseRequest, Object> answer;

    Script(BiFunction<Integer, TrustBaseRequest, Object> answer) {
      this.answer = answer;
    }

    static Script fixed(byte[] bytes) {
      return new Script((n, r) -> bytes);
    }

    @Override
    public CompletableFuture<byte[]> getTrustBasePage(TrustBaseRequest request) {
      int n = this.requests.size();
      this.requests.add(request);
      Object result = this.answer.apply(n, request);
      if (result instanceof Throwable) {
        return CompletableFuture.failedFuture((Throwable) result);
      }
      return CompletableFuture.completedFuture((byte[]) result);
    }
  }

  private static int update(TrustBaseHistory history, TrustBaseSource... sources) {
    return TrustBaseUpdater.update(TrustBaseUpdateTarget.of(history), Arrays.asList(sources), FAST);
  }

  private static long tip(TrustBaseHistory history) {
    return history.getTip().get().getEpoch();
  }

  @Test
  void aRangeFromTheNextEpochIsAppendedAndAuthenticated() {
    TrustBaseHistory history = genesisOnly();
    Script source = Script.fixed(page(2, 4, 4, null));
    assertEquals(3, update(history, source));
    assertEquals(4, tip(history));
    assertEquals(2, source.requests.get(0).getFromEpoch());
    assertNull(source.requests.get(0).getSnapshot());
  }

  @Test
  void aPagedRangeFollowsTheCursorAndEchoesTheSnapshot() {
    TrustBaseHistory history = genesisOnly();
    Script source = new Script((n, r) -> n == 0 ? page(2, 3, 5, 4L) : page(4, 5, 5, null));
    assertEquals(4, update(history, source));
    assertEquals(5, tip(history));
    assertNull(source.requests.get(0).getSnapshot());
    TrustBaseRequest.Snapshot echoed = source.requests.get(1).getSnapshot();
    assertEquals(5, echoed.getEnd());
    assertArrayEquals(entry(5).entryId(), echoed.getEntryId());
    assertEquals(4, source.requests.get(1).getFromEpoch());
  }

  @Test
  void nothingNewerIsAResultOnlyWhenEverySourceSaysIt() {
    TrustBaseHistory history = genesisOnly();
    Script none = Script.fixed(status(TrustBaseStatus.NOT_YET_AVAILABLE, 2, 1));
    assertEquals(0, update(history, none, none));
    // one source that serves nothing useful keeps it from being "current"
    Script broken = Script.fixed(new byte[] {1});
    TrustBaseUpdateException e = assertThrows(TrustBaseUpdateException.class,
        () -> update(history, none, broken));
    assertEquals(Failure.NONE_SERVED, e.getFailure());
  }

  @Test
  void aLaggingSourceDoesNotEndTheUpdateAndTheNextOneServes() {
    TrustBaseHistory history = genesisOnly();
    Script lagging = Script.fixed(status(TrustBaseStatus.NOT_YET_AVAILABLE, 2, 1));
    Script good = Script.fixed(page(2, 3, 3, null));
    assertEquals(2, update(history, lagging, good));
    assertEquals(3, tip(history));
  }

  @Test
  void aHostileStatusIsFinalForTheCallButBusyAndUnavailableAreAskedAgain() {
    TrustBaseHistory history = genesisOnly();
    Script hostile = Script.fixed(status(TrustBaseStatus.WRONG_NETWORK, 2, 6));
    Script good = Script.fixed(page(2, 3, 3, null));
    assertEquals(2, update(history, hostile, good));
    // a refusal is final: asked once, not again in the second pass
    TrustBaseHistory passes = genesisOnly();
    Script flakyGood = new Script((n, r) -> n == 0 ? status(TrustBaseStatus.BUSY, 2, 6)
        : page(2, 3, 3, null));
    Script hostile2 = Script.fixed(status(TrustBaseStatus.WRONG_NETWORK, 2, 6));
    assertEquals(2, update(passes, hostile2, flakyGood));
    assertEquals(1, hostile2.requests.size(), "asked once, not again in the second pass");

    for (TrustBaseStatus pause : new TrustBaseStatus[] {TrustBaseStatus.BUSY,
        TrustBaseStatus.UNAVAILABLE}) {
      TrustBaseHistory h = genesisOnly();
      Script flaky = new Script((n, r) -> n == 0 ? status(pause, 2, 6) : page(2, 3, 3, null));
      assertEquals(2, update(h, flaky), pause.name());
      assertEquals(2, flaky.requests.size(), pause.name());
    }
  }

  @Test
  void anEntryTheHistoryRefusesKeepsThePrefixAndTheNextSourceContinues() {
    TrustBaseHistory history = genesisOnly();
    // epoch 3 signed by one member only: refused, epoch 2 stays
    TrustBaseEntry weak = TrustBaseTestChain.successor(entry(2), COMMITTEE, COMMITTEE, 300L,
        TrustBaseTestChain.firstN(COMMITTEE, 1));
    Script damaged = Script.fixed(pageRaw(2, 3, weak.entryId(),
        List.of(entry(2).encode(), weak.encode()), null));
    Script good = Script.fixed(page(3, 4, 4, null));
    assertEquals(3, update(history, damaged, good));
    assertEquals(4, tip(history));
    assertEquals(3, good.requests.get(0).getFromEpoch(), "continues from the kept prefix");
  }

  @Test
  void aPageThatDoesNotContinueTheRangeIsSkipped() {
    TrustBaseHistory history = genesisOnly();
    // epochs that would append fine, but the page starts at 1 and the request was for 2
    Script wrongStart = Script.fixed(page(1, 3, 3, null));
    Script good = Script.fixed(page(2, 3, 3, null));
    assertEquals(2, update(history, wrongStart, good));
    assertEquals(1, good.requests.size(), "the page that did not continue was not accepted");
  }

  @Test
  void aFrozenSnapshotIsCheckedOnTheLaterPages() {
    TrustBaseHistory history = genesisOnly();
    // the second page announces another end: not continuing, the next source serves
    Script shifting = new Script((n, r) -> n == 0 ? page(2, 3, 5, 4L) : page(4, 4, 4, null));
    Script good = Script.fixed(page(4, 5, 5, null));
    assertEquals(4, update(history, shifting, good));
    assertEquals(5, tip(history));
  }

  @Test
  void nothingNewerMeansATipBelowTheRequestedEpochAndOnlyWithoutASnapshot() {
    TrustBaseHistory history = genesisOnly();
    // an end at or above the requested epoch is not "nothing newer"
    Script lying = Script.fixed(status(TrustBaseStatus.NOT_YET_AVAILABLE, 2, 2));
    assertThrows(TrustBaseUpdateException.class, () -> update(history, lying));
    // with a snapshot frozen, a not-yet-available page never ends the call as current
    TrustBaseHistory second = genesisOnly();
    Script paged = new Script((n, r) -> n == 0 ? page(2, 3, 5, 4L)
        : status(TrustBaseStatus.NOT_YET_AVAILABLE, 4, 1));
    TrustBaseUpdateException e = assertThrows(TrustBaseUpdateException.class,
        () -> update(second, paged));
    assertEquals(2, e.getAppended(), "the authenticated prefix is kept");
    assertEquals(3, tip(second));
  }

  @Test
  void anHonestSourceRefusingAFakeSnapshotIsAskedAgainWithoutIt() {
    TrustBaseHistory history = genesisOnly();
    byte[] fake = new byte[32];
    Script honest = new Script((n, r) -> {
      if (r.getSnapshot() != null) {
        return status(TrustBaseStatus.HISTORY_MISMATCH, 4, 5);
      }
      return page((int) r.getFromEpoch(), 5, 5, null);
    });
    // the first source freezes a fake end; the honest one refuses it, then serves without it
    Script hostileFirst = new Script((n, r) -> n == 0
        ? pageRaw(2, 9, fake, List.of(entry(2).encode()), 3L)
        : status(TrustBaseStatus.INVALID_REQUEST, 3, 9));
    assertEquals(4, update(history, hostileFirst, honest));
    assertEquals(5, tip(history));
    assertTrue(honest.requests.stream().anyMatch(r -> r.getSnapshot() != null));
    assertNull(honest.requests.get(honest.requests.size() - 1).getSnapshot(),
        "the last request was made without the dropped snapshot");
  }

  @Test
  void noSourceNoNetworkAndCancellation() throws Exception {
    TrustBaseHistory history = genesisOnly();
    assertEquals(Failure.NO_SOURCE,
        assertThrows(TrustBaseUpdateException.class, () -> update(history)).getFailure());

    TrustBaseHistory empty = TrustBaseHistory.create(entry(1).entryId());
    Script source = Script.fixed(page(1, 2, 2, null));
    assertEquals(Failure.NO_NETWORK, assertThrows(TrustBaseUpdateException.class,
        () -> TrustBaseUpdater.update(TrustBaseUpdateTarget.of(empty), List.of(source), FAST))
        .getFailure());
    // an empty history starts at the genesis epoch and the given network
    assertEquals(2, TrustBaseUpdater.update(TrustBaseUpdateTarget.of(empty), List.of(source),
        FAST.withNetwork(NET)));
    assertEquals(1, source.requests.get(0).getFromEpoch());

    // cancelling the future cancels the request in flight
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      CompletableFuture<byte[]> never = new CompletableFuture<>();
      AtomicInteger calls = new AtomicInteger();
      TrustBaseSource hanging = request -> {
        calls.incrementAndGet();
        return never;
      };
      CompletableFuture<Integer> run = TrustBaseUpdater.updateAsync(
          TrustBaseUpdateTarget.of(genesisOnly()), List.of(hanging), FAST, executor);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (calls.get() == 0 && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertEquals(1, calls.get());
      assertTrue(run.cancel(true));
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!never.isCancelled() && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertTrue(never.isCancelled(), "the in-flight request was cancelled");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void aSourceFailureIsACauseNotAnAbort() {
    TrustBaseHistory history = genesisOnly();
    Script down = new Script((n, r) -> new IOException("down"));
    Script good = Script.fixed(page(2, 2, 2, null));
    assertEquals(1, update(history, down, good));
    TrustBaseHistory second = genesisOnly();
    TrustBaseUpdateException e = assertThrows(TrustBaseUpdateException.class,
        () -> update(second, down));
    assertEquals(CauseKind.SOURCE, e.getCauses().get(0).getKind());
    assertTrue(e.getCauses().get(0).getError() instanceof IOException);
    assertEquals(2, e.getCauses().size(), "asked once per round");
  }

  private static boolean missing(Integer epochNeeded, TrustBaseHistory history) {
    return !history.entryForEpoch(epochNeeded).isPresent();
  }

  @Test
  void verifyWithUpdateFetchesOnlyForTheMissingEntryAndRetriesOnce() {
    TrustBaseHistory history = genesisOnly();
    TrustBaseUpdateTarget target = TrustBaseUpdateTarget.of(history);
    AtomicInteger verifications = new AtomicInteger();
    // verifying a seal of epoch 3
    java.util.function.Supplier<String> verify = () -> {
      verifications.incrementAndGet();
      return missing(3, history) ? "NO_ENTRY" : "OK";
    };
    Script source = Script.fixed(page(2, 3, 3, null));

    // already verifiable: no fetch
    TrustBaseHistory full = genesisOnly();
    full.append(entry(2));
    full.append(entry(3));
    assertEquals("OK", TrustBaseUpdater.verifyWithUpdate(TrustBaseUpdateTarget.of(full),
        List.of(source), FAST, () -> "OK", "NO_ENTRY"::equals));
    assertEquals(0, source.requests.size());

    assertEquals("OK", TrustBaseUpdater.verifyWithUpdate(target, List.of(source), FAST, verify,
        "NO_ENTRY"::equals));
    assertEquals(2, verifications.get(), "one verification, one update, one retry");

    // another failure is final: no fetch
    Script untouched = Script.fixed(page(2, 3, 3, null));
    assertEquals("BAD", TrustBaseUpdater.verifyWithUpdate(target, List.of(untouched), FAST,
        () -> "BAD", "NO_ENTRY"::equals));
    assertEquals(0, untouched.requests.size());

    // still missing after the update: the retry is the last, never a loop
    AtomicInteger calls = new AtomicInteger();
    Script servesTwo = Script.fixed(page(2, 3, 3, null));
    TrustBaseHistory third = genesisOnly();
    assertEquals("NO_ENTRY", TrustBaseUpdater.verifyWithUpdate(TrustBaseUpdateTarget.of(third),
        List.of(servesTwo), FAST, () -> {
          calls.incrementAndGet();
          return "NO_ENTRY";
        }, "NO_ENTRY"::equals));
    assertEquals(2, calls.get());
  }

  @Test
  void aNothingNewerUpdateKeepsTheFirstResultAndDoesNotRetry() {
    TrustBaseHistory history = genesisOnly();
    Script none = Script.fixed(status(TrustBaseStatus.NOT_YET_AVAILABLE, 2, 1));
    AtomicInteger calls = new AtomicInteger();
    String result = TrustBaseUpdater.verifyWithUpdate(TrustBaseUpdateTarget.of(history),
        List.of(none), FAST, () -> {
          calls.incrementAndGet();
          return "NO_ENTRY";
        }, "NO_ENTRY"::equals);
    assertEquals("NO_ENTRY", result);
    assertEquals(1, calls.get());
  }

  @Test
  void aLaterPageFailingAfterTheNeededEntryArrivedStillVerifies() {
    TrustBaseHistory history = genesisOnly();
    // page 1 carries epochs 2..3 (the needed one), the cursor page then fails
    Script source = new Script((n, r) -> n == 0 ? page(2, 3, 6, 4L) : new IOException("cut"));
    String result = TrustBaseUpdater.verifyWithUpdate(TrustBaseUpdateTarget.of(history),
        List.of(source), FAST, () -> missing(3, history) ? "NO_ENTRY" : "OK", "NO_ENTRY"::equals);
    assertEquals("OK", result);

    // the prefix does not help: the failure is reported with the kept prefix
    TrustBaseHistory second = genesisOnly();
    Script cut = new Script((n, r) -> n == 0 ? page(2, 2, 6, 3L) : new IOException("cut"));
    TrustBaseUpdateException e = assertThrows(TrustBaseUpdateException.class,
        () -> TrustBaseUpdater.verifyWithUpdate(TrustBaseUpdateTarget.of(second), List.of(cut),
            FAST, () -> missing(3, second) ? "NO_ENTRY" : "OK", "NO_ENTRY"::equals));
    assertEquals(1, e.getAppended());
    assertNotNull(e.getMessage());
  }

  @Test
  void anAuthenticatedForkIsForTheApplicationNotForTheNextSource() {
    TrustBaseHistory history = genesisOnly();
    history.append(entry(2));
    // a different, fully quorum-signed epoch 2 (another start round)
    TrustBaseEntry fork = TrustBaseTestChain.successor(entry(1), COMMITTEE, COMMITTEE, 999L,
        TrustBaseTestChain.firstN(COMMITTEE, 3));
    TrustBaseUpdateTarget forking = new TrustBaseUpdateTarget() {
      @Override
      public java.util.Optional<NetworkId> getNetworkId() {
        return history.getNetworkId();
      }

      @Override
      public java.util.Optional<TrustBaseEntry> getTip() {
        return history.getTip();
      }

      @Override
      public TrustBaseAppendOutcome append(TrustBaseEntry entry) {
        throw new TrustBaseConflictException(2, new TrustBaseConflictDetails(true, 2, null,
            entry(2).entryId(), NETWORK, null, fork.entryId()));
      }
    };
    Script serves = Script.fixed(page(3, 3, 3, null));
    Script next = Script.fixed(page(3, 3, 3, null));
    assertThrows(TrustBaseConflictException.class,
        () -> TrustBaseUpdater.update(forking, List.of(serves, next), FAST));
    assertEquals(0, next.requests.size());
  }
}
