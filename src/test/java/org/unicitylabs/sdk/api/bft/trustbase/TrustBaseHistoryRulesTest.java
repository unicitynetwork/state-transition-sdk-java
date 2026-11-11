package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseTestChain.Committee;
import org.unicitylabs.sdk.util.HexConverter;

/** History rules one at a time, weighted quorums, 256 to 4, the maximum entry, concurrency. */
class TrustBaseHistoryRulesTest {

  private static final JsonNode MAX = TrustBaseVectors.maxEntry();
  private static final TrustBaseBundleLimits LIMITS = new TrustBaseBundleLimits(1 << 20, 64);

  private final Committee c1 = new Committee(4, 1, 1);
  private final Committee c2 = new Committee(4, 1, 2);
  private final Committee c3 = new Committee(4, 1, 3);
  private final TrustBaseEntry g = TrustBaseTestChain.genesis(5, 1, 1, c1);
  private final TrustBaseEntry e2 =
      TrustBaseTestChain.successor(g, c1, c2, 10, TrustBaseTestChain.firstN(c1, 3));
  private final TrustBaseEntry e3 =
      TrustBaseTestChain.successor(e2, c2, c3, 20, TrustBaseTestChain.firstN(c2, 3));

  private TrustBaseHistory pinned() {
    TrustBaseHistory history = TrustBaseHistory.create(g.entryId());
    history.append(g);
    return history;
  }

  @Test
  void startsEmptyAndAcceptsThePinnedGenesisOnly() {
    TrustBaseHistory history = TrustBaseHistory.create(g.entryId());
    assertTrue(history.isEmpty());
    assertFalse(history.getTip().isPresent());
    assertFalse(history.getNetworkId().isPresent());
    assertEquals(TrustBaseAppendOutcome.APPENDED, history.append(g));
    assertFalse(history.isEmpty());
    assertEquals(1, history.getTip().get().getEpoch());
    assertEquals(5, history.getNetworkId().get().getId());
  }

  @Test
  void keepsItsOwnCopyOfThePinAndAnswersACopy() {
    byte[] pin = g.entryId();
    TrustBaseHistory history = TrustBaseHistory.create(pin);
    java.util.Arrays.fill(pin, (byte) 0);
    assertEquals(TrustBaseAppendOutcome.APPENDED, history.append(g));
    byte[] answered = history.getGenesisPin();
    java.util.Arrays.fill(answered, (byte) 0);
    assertArrayEquals(g.entryId(), history.getGenesisPin());
  }

  @Test
  void refusesAGenesisThatIsNotThePinnedIdentityAndAPinThatIsNot32Bytes() {
    TrustBaseHistory history = TrustBaseHistory.create(new byte[32]);
    TrustBaseChainException e = assertThrows(TrustBaseChainException.class, () -> history.append(g));
    assertTrue(e.getMessage().contains("not the pinned identity"));
    assertTrue(history.isEmpty());
    assertThrows(TrustBaseInvalidException.class, () -> TrustBaseHistory.create(new byte[31]));
  }

  @Test
  void refusesAFirstEntryThatIsNotAGenesisWhateverThePin() {
    TrustBaseHistory history = TrustBaseHistory.create(e2.entryId());
    assertThrows(TrustBaseInvalidException.class, () -> history.append(e2));
  }

  @Test
  void appendsConsecutiveEntriesAndReSentOnesAreAlreadyHeld() {
    TrustBaseHistory history = pinned();
    assertEquals(TrustBaseAppendOutcome.APPENDED, history.append(e2));
    assertEquals(TrustBaseAppendOutcome.ALREADY_HELD, history.append(e2));
    assertEquals(TrustBaseAppendOutcome.ALREADY_HELD, history.append(g));
    TrustBaseEntry otherQuorum =
        TrustBaseTestChain.successor(g, c1, c2, 10, List.of("m0001", "m0002", "m0003"));
    assertArrayEquals(e2.entryId(), otherQuorum.entryId());
    assertEquals(TrustBaseAppendOutcome.ALREADY_HELD, history.append(otherQuorum));
    assertEquals(2, history.getEntries().size());
  }

  @Test
  void aBadWitnessForAHeldEpochIsAnErrorNotAnAlreadyHeld() {
    TrustBaseHistory history = pinned();
    history.append(e2);
    TrustBaseEntry badWitness =
        TrustBaseTestChain.successor(g, c1, c2, 10, TrustBaseTestChain.firstN(c1, 2));
    assertThrows(TrustBaseSignatureException.class, () -> history.append(badWitness));
  }

  @Test
  void aGapIsReportedWithBothEpochsAndAppendsNothing() {
    TrustBaseHistory history = pinned();
    TrustBaseGapException gap = assertThrows(TrustBaseGapException.class, () -> history.append(e3));
    assertEquals(3, gap.getEpoch());
    assertEquals(1, gap.getTipEpoch());
    assertEquals(1, history.getEntries().size());
  }

  @Test
  void aDifferentEntryForAHeldEpochIsAConflictWithBothIdsAndNothingChanges() {
    TrustBaseHistory history = pinned();
    history.append(e2);
    Committee other = new Committee(4, 1, 9);
    TrustBaseEntry fork = TrustBaseTestChain.successor(g, c1, other, 10, TrustBaseTestChain.firstN(c1, 3));
    TrustBaseConflictException e =
        assertThrows(TrustBaseConflictException.class, () -> history.append(fork));
    assertEquals(2, e.getEpoch());
    assertFalse(e.isAuthenticated());
    assertArrayEquals(e2.entryId(), e.getDetails().getHeldEntryId());
    assertArrayEquals(fork.entryId(), e.getDetails().getOfferedEntryId());
    assertEquals(2, history.getEntries().size());
    assertArrayEquals(e2.entryId(), history.getTip().get().entryId());
  }

  @Test
  void anEntryBeforeTheGenesisEpochIsRefusedAsSuch() {
    TrustBaseEntry late = TrustBaseTestChain.genesis(5, 4, 100, c1);
    TrustBaseHistory history = TrustBaseHistory.create(late.entryId());
    history.append(late);
    TrustBaseEntry early = TrustBaseTestChain.genesis(5, 2, 50, c2);
    assertThrows(TrustBaseBeforeGenesisException.class, () -> history.append(early));
  }

  @Test
  void aSuccessorWithAWrongPredecessorOrNetworkOrStartRoundIsAChainError() {
    TrustBaseHistory history = pinned();
    // predecessor link of another chain
    TrustBaseEntry elsewhere = TrustBaseTestChain.successor(TrustBaseTestChain.genesis(5, 1, 2, c3),
        c3, c2, 10, TrustBaseTestChain.firstN(c3, 3));
    assertThrows(TrustBaseChainException.class, () -> history.append(elsewhere));
    // a start round that does not advance
    TrustBaseEntry flat = TrustBaseTestChain.successor(g, c1, c2, 1, TrustBaseTestChain.firstN(c1, 3));
    assertThrows(TrustBaseChainException.class, () -> history.append(flat));
    // another network
    TrustBaseEntry g6 = TrustBaseTestChain.genesis(6, 1, 1, c1);
    TrustBaseEntry n6 = TrustBaseTestChain.successor(g6, c1, c2, 10, TrustBaseTestChain.firstN(c1, 3));
    assertThrows(TrustBaseChainException.class, () -> history.append(n6));
    assertEquals(1, history.getEntries().size());
  }

  @Test
  void aStartRoundAbove2To63OrdersAsUnsignedAndEpochsCountAsUnsigned() {
    long high = Long.MIN_VALUE + 5; // 2^63 + 5
    TrustBaseEntry e = TrustBaseTestChain.successor(g, c1, c2, high, TrustBaseTestChain.firstN(c1, 3));
    TrustBaseHistory history = pinned();
    history.append(e);
    TrustBaseEntry next = TrustBaseTestChain.successor(e, c2, c3, -1L, TrustBaseTestChain.firstN(c2, 3));
    history.append(next);
    // a round that would be larger signed but is smaller unsigned
    TrustBaseEntry back = TrustBaseTestChain.successor(next, c3, c1, 7, TrustBaseTestChain.firstN(c3, 3));
    assertThrows(TrustBaseChainException.class, () -> history.append(back));
    assertEquals(high, history.epochEndRound(1).getAsLong());
  }

  @Test
  void epochsCrossingTwoToThe63CountAsUnsigned() {
    TrustBaseEntry high = TrustBaseTestChain.genesis(5, Long.MAX_VALUE, 1, c1);
    TrustBaseHistory history = TrustBaseHistory.create(high.entryId());
    history.append(high);
    TrustBaseEntry next = TrustBaseTestChain.successor(high, c1, c2, 10, TrustBaseTestChain.firstN(c1, 3));
    assertEquals(Long.MIN_VALUE, next.getEpoch());
    assertEquals(TrustBaseAppendOutcome.APPENDED, history.append(next));
    assertEquals(TrustBaseAppendOutcome.ALREADY_HELD, history.append(next));
    assertTrue(history.entryForEpoch(Long.MIN_VALUE).isPresent());
    assertEquals(10, history.epochEndRound(Long.MAX_VALUE).getAsLong());
    // two epochs ahead is a gap, not "an older entry"
    TrustBaseEntry far = TrustBaseTestChain.successor(next, c2, c3, 20, TrustBaseTestChain.firstN(c2, 3));
    TrustBaseHistory fresh = TrustBaseHistory.create(high.entryId());
    fresh.append(high);
    assertThrows(TrustBaseGapException.class, () -> fresh.append(far));
  }

  @Test
  void entryForEpochAndEpochEndRound() {
    TrustBaseHistory history = pinned();
    history.append(e2);
    assertFalse(history.entryForEpoch(0).isPresent());
    assertTrue(history.entryForEpoch(1).isPresent());
    assertTrue(history.entryForEpoch(2).isPresent());
    assertFalse(history.entryForEpoch(3).isPresent());
    assertFalse(history.entryForEpoch(-1L).isPresent());
    assertEquals(10, history.epochEndRound(1).getAsLong());
    assertFalse(history.epochEndRound(2).isPresent());
    assertFalse(history.epochEndRound(-1L).isPresent());
  }

  @Test
  void aWeightedQuorumNeedsMoreThanTwoThirdsOfTheWeightNotOfTheSigners() {
    // weights 5,1,1,1: a alone is 5 of 6 needed, a+b is 6
    List<TrustBaseMember> members = new ArrayList<>();
    Committee base = new Committee(4, 1, 4);
    long[] weights = {5, 1, 1, 1};
    for (int i = 0; i < 4; i++) {
      TrustBaseMember m = base.members.get(i);
      members.add(new TrustBaseMember(m.getNodeId(), m.getSigKey(), weights[i]));
    }
    TrustBaseEntry heavy = new TrustBaseEntry(5, 1, 1, members, 6, null, null, new byte[32], null);
    heavy.validate();
    TrustBaseHistory history = TrustBaseHistory.create(heavy.entryId());
    history.append(heavy);
    for (List<String> signers : List.of(List.of("m0000"), List.of("m0001", "m0002", "m0003"))) {
      TrustBaseEntry below = successorOf(heavy, base, signers);
      assertThrows(TrustBaseSignatureException.class, () -> history.append(below));
    }
    history.append(successorOf(heavy, base, List.of("m0000", "m0001")));
  }

  private TrustBaseEntry successorOf(TrustBaseEntry previous, Committee signing, List<String> signers) {
    return TrustBaseTestChain.successor(previous, signing, c2, 10, signers);
  }

  @Test
  void overlapOfTwoSignerSetsIsEnoughTheSameEntryIsHeldOnce() {
    Committee big = new Committee(256, 1, 5);
    TrustBaseEntry big1 = TrustBaseTestChain.genesis(5, 1, 1, big);
    assertEquals(171, big1.getQuorumThreshold());
    TrustBaseHistory history = TrustBaseHistory.create(big1.entryId());
    history.append(big1);
    TrustBaseEntry exact = TrustBaseTestChain.successor(big1, big, c2, 10, TrustBaseTestChain.firstN(big, 171));
    assertEquals(TrustBaseAppendOutcome.APPENDED, history.append(exact));
    List<String> tail = new ArrayList<>(big.secrets.keySet()).subList(85, 256);
    TrustBaseEntry overlap = TrustBaseTestChain.successor(big1, big, c2, 10, tail);
    assertEquals(171, tail.size());
    assertEquals(TrustBaseAppendOutcome.ALREADY_HELD, history.append(overlap));
    TrustBaseEntry short1 = TrustBaseTestChain.successor(big1, big, c2, 10, TrustBaseTestChain.firstN(big, 170));
    assertThrows(TrustBaseSignatureException.class, () -> new TrustBaseHistoryProbe(big1).append(short1));
  }

  /** A fresh history holding only the genesis. */
  private static final class TrustBaseHistoryProbe {

    private final TrustBaseHistory history;

    TrustBaseHistoryProbe(TrustBaseEntry genesis) {
      this.history = TrustBaseHistory.create(genesis.entryId());
      this.history.append(genesis);
    }

    void append(TrustBaseEntry entry) {
      this.history.append(entry);
    }
  }

  @Test
  void a256MemberCommitteeHandsOverToFourMembersWith256PredecessorSignatures() {
    Committee big = new Committee(256, 1, 6);
    TrustBaseEntry big1 = TrustBaseTestChain.genesis(5, 1, 1, big);
    Committee four = new Committee(4, 1, 7);
    TrustBaseEntry small = TrustBaseTestChain.successor(big1, big, four, 10, new ArrayList<>(big.secrets.keySet()));
    assertEquals(256, small.getSignatures().size());
    assertEquals(4, small.getMembers().size());
    TrustBaseEntry decoded = TrustBaseEntry.decode(small.encode());
    TrustBaseHistory history = TrustBaseHistory.create(big1.entryId());
    history.append(big1);
    assertEquals(TrustBaseAppendOutcome.APPENDED, history.append(decoded));
    assertEquals(4, history.getTip().get().getMembers().size());
  }

  @Test
  void theMaximumEntryBundleDecodesAndInstallsFromItsPin() {
    byte[] bytes = HexConverter.decode(MAX.get("bundle").asText());
    TrustBaseBundle bundle = TrustBaseBundle.decode(bytes, LIMITS);
    assertEquals(2, bundle.getEntries().size());
    assertEquals(MAX.get("genesisId").asText(), HexConverter.encode(bundle.getEntries().get(0).entryId()));
    assertEquals(MAX.get("entryId").asText(), HexConverter.encode(bundle.getTip().entryId()));
    TrustBaseHistory history =
        TrustBaseHistory.fromBundle(bundle, HexConverter.decode(MAX.get("genesisId").asText()));
    assertEquals(2, history.getEntries().size());
    assertArrayEquals(bytes, history.toBundle());
    assertEquals(MAX.get("members").asInt(), bundle.getEntries().get(0).getMembers().size());
  }

  @Test
  void concurrentAppendersSeeOneConsistentChain() throws Exception {
    TrustBaseHistory history = pinned();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Object>> tasks = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        final int n = i;
        tasks.add(() -> {
          try {
            return history.append(n % 2 == 0 ? e2 : e3);
          } catch (TrustBaseGapException e) {
            return e;
          }
        });
      }
      for (Future<Object> f : pool.invokeAll(tasks)) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
    List<TrustBaseEntry> held = history.getEntries();
    assertTrue(held.size() == 2 || held.size() == 3);
    for (int i = 1; i < held.size(); i++) {
      assertArrayEquals(held.get(i - 1).entryId(), held.get(i).getPreviousEntryId());
    }
    assertEquals(1, held.stream().filter(e -> e.getEpoch() == 2).count());
  }
}
