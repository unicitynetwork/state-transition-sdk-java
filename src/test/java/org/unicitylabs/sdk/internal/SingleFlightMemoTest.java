package org.unicitylabs.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SingleFlightMemoTest {

  @Test
  void aSuccessIsComputedOnceWhileItIsResident() {
    SingleFlightMemo<String> memo = new SingleFlightMemo<>(4, 4, "ok"::equals);
    AtomicInteger computations = new AtomicInteger();
    for (int i = 0; i < 5; i++) {
      SingleFlightMemo.Outcome<String> outcome = memo.get("k", () -> {
        computations.incrementAndGet();
        return "ok";
      });
      assertEquals(i == 0 ? SingleFlightMemo.Source.COMPUTED : SingleFlightMemo.Source.HIT,
          outcome.getSource());
    }
    assertEquals(1, computations.get());
  }

  @Test
  void aFailureIsNeverRetained() {
    SingleFlightMemo<String> memo = new SingleFlightMemo<>(4, 4, "ok"::equals);
    AtomicInteger computations = new AtomicInteger();
    for (int i = 0; i < 3; i++) {
      memo.get("k", () -> {
        computations.incrementAndGet();
        return "fail";
      });
    }
    assertEquals(3, computations.get());
    assertEquals(0, memo.size());
    assertEquals(0, memo.inFlightCount());
  }

  @Test
  void aThrowingComputationLeavesNothingBehind() {
    SingleFlightMemo<String> memo = new SingleFlightMemo<>(4, 4, "ok"::equals);
    assertThrows(IllegalStateException.class, () -> memo.get("k", () -> {
      throw new IllegalStateException("boom");
    }));
    assertEquals(0, memo.inFlightCount());
    assertEquals("ok", memo.get("k", () -> "ok").getValue());
  }

  @Test
  void theOldestEntryIsEvictedWhenTheBoundIsReached() {
    SingleFlightMemo<String> memo = new SingleFlightMemo<>(2, 4, v -> true);
    AtomicInteger computations = new AtomicInteger();
    for (String key : new String[] {"a", "b", "c", "a"}) {
      memo.get(key, () -> {
        computations.incrementAndGet();
        return key;
      });
    }
    // a and b fill it, c evicts a, a is computed again and evicts b
    assertEquals(4, computations.get());
    assertEquals(2, memo.size());
    assertEquals(SingleFlightMemo.Source.HIT, memo.get("c", () -> "x").getSource());
    assertEquals(SingleFlightMemo.Source.HIT, memo.get("a", () -> "x").getSource());
    assertEquals(SingleFlightMemo.Source.COMPUTED, memo.get("b", () -> "b").getSource());
  }

  @Test
  void aConcurrentCallerJoinsTheRunningComputation() throws Exception {
    SingleFlightMemo<String> memo = new SingleFlightMemo<>(4, 4, v -> true);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger computations = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<SingleFlightMemo.Outcome<String>> first = CompletableFuture.supplyAsync(
          () -> memo.get("k", () -> {
            computations.incrementAndGet();
            started.countDown();
            await(release);
            return "value";
          }), pool);
      assertTrue(started.await(5, TimeUnit.SECONDS));
      CompletableFuture<SingleFlightMemo.Outcome<String>> second = CompletableFuture.supplyAsync(
          () -> memo.get("k", () -> {
            computations.incrementAndGet();
            return "second";
          }), pool);
      // the second caller is waiting on the first one
      Thread.sleep(100);
      assertEquals(1, memo.inFlightCount());
      release.countDown();
      assertEquals("value", first.get(5, TimeUnit.SECONDS).getValue());
      SingleFlightMemo.Outcome<String> joined = second.get(5, TimeUnit.SECONDS);
      assertEquals("value", joined.getValue());
      assertEquals(SingleFlightMemo.Source.JOINED, joined.getSource());
      assertEquals(1, computations.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aJoinerComputesForItselfWhenTheFirstComputationThrew() throws Exception {
    SingleFlightMemo<String> memo = new SingleFlightMemo<>(4, 4, v -> true);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<Object> first = CompletableFuture.supplyAsync(() -> {
        try {
          return memo.get("k", () -> {
            started.countDown();
            await(release);
            throw new IllegalStateException("first fails");
          });
        } catch (IllegalStateException e) {
          return e;
        }
      }, pool);
      assertTrue(started.await(5, TimeUnit.SECONDS));
      CompletableFuture<SingleFlightMemo.Outcome<String>> second = CompletableFuture.supplyAsync(
          () -> memo.get("k", () -> "second"), pool);
      Thread.sleep(100);
      release.countDown();
      assertTrue(first.get(5, TimeUnit.SECONDS) instanceof IllegalStateException);
      assertEquals("second", second.get(5, TimeUnit.SECONDS).getValue());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aLimitOfOneComputationInFlightRefusesTheNextDistinctKey() throws Exception {
    SingleFlightMemo<String> memo = new SingleFlightMemo<>(4, 1, v -> true);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      CompletableFuture<SingleFlightMemo.Outcome<String>> first = CompletableFuture.supplyAsync(
          () -> memo.get("a", () -> {
            started.countDown();
            await(release);
            return "a";
          }), pool);
      assertTrue(started.await(5, TimeUnit.SECONDS));
      SingleFlightMemo.BusyException busy = assertThrows(SingleFlightMemo.BusyException.class,
          () -> memo.get("b", () -> "b"));
      assertEquals(1, busy.getMaxInFlight());
      release.countDown();
      assertSame("a", first.get(5, TimeUnit.SECONDS).getValue());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void limitsMustBePositive() {
    assertThrows(IllegalArgumentException.class, () -> new SingleFlightMemo<String>(0, 1, v -> true));
    assertThrows(IllegalArgumentException.class, () -> new SingleFlightMemo<String>(1, 0, v -> true));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
