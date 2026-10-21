package org.unicitylabs.sdk.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Independent timer of operation deadlines. A deadline must bound an operation whatever the state
 * of the executor that does its CPU work, so its callback never runs on that executor: one daemon
 * timer thread runs it, and the callback only completes a future and cancels outstanding calls.
 *
 * <p>Every schedule returns a handle, and {@link #release} cancels it and removes it from the
 * timer's queue at once, so a finished operation retains nothing until its original timeout.
 *
 * <p>Internal API, not part of the stable surface.
 */
public final class Deadlines {

  private static final ScheduledThreadPoolExecutor TIMER = create();

  private Deadlines() {
  }

  private static ScheduledThreadPoolExecutor create() {
    ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
      Thread thread = new Thread(task, "unicity-sdk-deadlines");
      thread.setDaemon(true);
      return thread;
    });
    timer.setRemoveOnCancelPolicy(true);
    timer.setKeepAliveTime(10, TimeUnit.SECONDS);
    timer.allowCoreThreadTimeOut(true);
    return timer;
  }

  /**
   * Schedules an action after a delay.
   *
   * @param action action to run on the timer thread; it must be short and non-blocking
   * @param delayNanos delay in nanoseconds
   * @return handle to release
   */
  public static ScheduledFuture<?> schedule(Runnable action, long delayNanos) {
    return TIMER.schedule(action, Math.max(0, delayNanos), TimeUnit.NANOSECONDS);
  }

  /**
   * Schedules an action and ties the timer to a future: whichever way the future ends, including
   * when it already has ended, the timer is released.
   *
   * @param action deadline action
   * @param delayNanos delay in nanoseconds
   * @param operation future of the operation
   */
  public static void scheduleFor(Runnable action, long delayNanos, CompletableFuture<?> operation) {
    ScheduledFuture<?> handle = schedule(action, delayNanos);
    operation.whenComplete((result, error) -> release(handle));
  }

  /**
   * Releases a timer.
   *
   * @param handle handle from {@link #schedule}
   */
  public static void release(ScheduledFuture<?> handle) {
    handle.cancel(false);
  }

  /**
   * Number of timers waiting, for tests that check an operation released its timer.
   *
   * @return queued timers
   */
  public static int pending() {
    return TIMER.getQueue().size();
  }
}
