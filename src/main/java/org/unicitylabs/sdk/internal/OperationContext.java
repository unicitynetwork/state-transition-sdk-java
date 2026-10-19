package org.unicitylabs.sdk.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Budget and cancellation of one SDK operation (a wait, a consolidation, a verification).
 *
 * <p>The deadline is monotonic ({@link System#nanoTime()} by default) and covers the whole
 * operation: every retry, the parsing and the verification draw on the same budget. Work started
 * for the operation registers a cancellation action and removes it when it finishes; cancelling
 * the operation runs the registered actions once. {@code CompletableFuture.cancel} does not reach
 * an in-flight HTTP call by itself, so the operation owner links the two explicitly.
 *
 * <p>Internal API, not part of the stable surface.
 */
public final class OperationContext {

  private final LongSupplier nanoClock;
  private final long deadlineNanos;
  private final boolean bounded;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final CopyOnWriteArrayList<Runnable> actions = new CopyOnWriteArrayList<>();

  private OperationContext(LongSupplier nanoClock, long deadlineNanos, boolean bounded) {
    this.nanoClock = nanoClock;
    this.deadlineNanos = deadlineNanos;
    this.bounded = bounded;
  }

  /**
   * Creates a context without a deadline.
   *
   * @return unbounded context
   */
  public static OperationContext unbounded() {
    return new OperationContext(System::nanoTime, 0, false);
  }

  /**
   * Creates a context whose deadline is {@code budget} from now.
   *
   * @param budget non-negative budget
   * @return bounded context
   * @throws IllegalArgumentException if the budget is null or negative
   */
  public static OperationContext withBudget(Duration budget) {
    return withBudget(budget, System::nanoTime);
  }

  /**
   * Creates a context with an explicit monotonic clock, for tests that count rather than sleep.
   *
   * @param budget non-negative budget
   * @param nanoClock monotonic clock in nanoseconds
   * @return bounded context
   * @throws IllegalArgumentException if the budget is null or negative
   */
  public static OperationContext withBudget(Duration budget, LongSupplier nanoClock) {
    if (budget == null || budget.isNegative()) {
      throw new IllegalArgumentException("Budget must be a non-negative duration.");
    }
    Objects.requireNonNull(nanoClock, "nanoClock cannot be null");
    long nanos;
    try {
      nanos = budget.toNanos();
    } catch (ArithmeticException e) {
      nanos = Long.MAX_VALUE / 4;
    }
    return new OperationContext(nanoClock, nanoClock.getAsLong() + Math.min(nanos, Long.MAX_VALUE / 4),
        true);
  }

  /**
   * Time left of the budget.
   *
   * @return remaining time, zero when expired; {@link Duration#ofNanos} of {@link Long#MAX_VALUE}
   *     for an unbounded context
   */
  public Duration remaining() {
    if (!bounded) {
      return Duration.ofNanos(Long.MAX_VALUE);
    }
    return Duration.ofNanos(Math.max(0, deadlineNanos - nanoClock.getAsLong()));
  }

  /**
   * Tells if the budget is used up.
   *
   * @return true if the deadline passed
   */
  public boolean isExpired() {
    return bounded && deadlineNanos - nanoClock.getAsLong() <= 0;
  }

  /**
   * Tells if the operation was cancelled.
   *
   * @return true after {@link #cancel()}
   */
  public boolean isCancelled() {
    return cancelled.get();
  }

  /**
   * Tells if work for the operation may no longer start or publish a result.
   *
   * @return true if cancelled or expired
   */
  public boolean isDone() {
    return isCancelled() || isExpired();
  }

  /**
   * Cancels the operation and runs every registered action once. All actions run even if one of
   * them throws: the registrations are drained first, and the first failure is rethrown afterwards
   * with the others as suppressed exceptions, so no outstanding work is left uncancelled.
   *
   * @return true if this call cancelled the operation, false if it was already cancelled
   */
  public boolean cancel() {
    if (!cancelled.compareAndSet(false, true)) {
      return false;
    }
    RuntimeException failure = null;
    Error error = null;
    for (Runnable action : actions) {
      if (!actions.remove(action)) {
        continue;
      }
      try {
        action.run();
      } catch (RuntimeException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      } catch (Error e) {
        if (error == null) {
          error = e;
        } else {
          error.addSuppressed(e);
        }
      }
    }
    if (error != null) {
      throw error;
    }
    if (failure != null) {
      throw failure;
    }
    return true;
  }

  /**
   * Registers an action that runs when the operation is cancelled. If it is cancelled already the
   * action runs immediately, so a registration racing with a cancellation never leaks work.
   *
   * @param action cancellation action, runs at most once
   * @return registration that removes the action; call it when the work finished
   */
  public Registration onCancel(Runnable action) {
    Objects.requireNonNull(action, "action cannot be null");
    actions.add(action);
    if (cancelled.get() && actions.remove(action)) {
      action.run();
    }
    return () -> actions.remove(action);
  }

  /** Removes a cancellation action. */
  @FunctionalInterface
  public interface Registration {

    /** Removes the registered action; harmless after it ran. */
    void remove();
  }
}
