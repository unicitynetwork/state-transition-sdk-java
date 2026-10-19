package org.unicitylabs.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OperationContextTest {

  @Test
  void deadlineFollowsTheInjectedMonotonicClock() {
    AtomicLong now = new AtomicLong(1_000);
    OperationContext context = OperationContext.withBudget(Duration.ofNanos(500), now::get);
    assertEquals(Duration.ofNanos(500), context.remaining());
    assertFalse(context.isExpired());
    now.addAndGet(499);
    assertFalse(context.isExpired());
    now.addAndGet(1);
    assertTrue(context.isExpired());
    assertTrue(context.isDone());
    assertEquals(Duration.ZERO, context.remaining());
  }

  @Test
  void negativeBudgetIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> OperationContext.withBudget(Duration.ofSeconds(-1)));
  }

  @Test
  void cancelRunsRegisteredActionsOnce() {
    OperationContext context = OperationContext.unbounded();
    AtomicInteger calls = new AtomicInteger();
    context.onCancel(calls::incrementAndGet);
    OperationContext.Registration removed = context.onCancel(calls::incrementAndGet);
    removed.remove();
    assertTrue(context.cancel());
    assertFalse(context.cancel());
    assertEquals(1, calls.get());
    assertTrue(context.isCancelled());
  }

  @Test
  void registrationAfterCancellationRunsImmediately() {
    OperationContext context = OperationContext.unbounded();
    context.cancel();
    AtomicInteger calls = new AtomicInteger();
    context.onCancel(calls::incrementAndGet);
    assertEquals(1, calls.get());
  }

  @Test
  void cancelRunsEveryActionEvenIfOneThrows() {
    OperationContext context = OperationContext.unbounded();
    AtomicInteger calls = new AtomicInteger();
    context.onCancel(() -> {
      calls.incrementAndGet();
      throw new IllegalStateException("first");
    });
    context.onCancel(calls::incrementAndGet);
    context.onCancel(() -> {
      calls.incrementAndGet();
      throw new IllegalArgumentException("third");
    });
    IllegalStateException thrown = assertThrows(IllegalStateException.class, context::cancel);
    assertEquals(3, calls.get());
    assertEquals(1, thrown.getSuppressed().length);
    assertTrue(context.isCancelled());
    assertFalse(context.cancel());
    assertEquals(3, calls.get());
  }
}
