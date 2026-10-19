package org.unicitylabs.sdk.internal;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Bounded memo of successful results with sharing of computations in flight.
 *
 * <p>A key is computed once while its result is resident: a concurrent caller with the same key
 * joins the running computation instead of repeating it. Only results the caller accepts as
 * retainable stay in the memo (successes); a result that is not retained, and a computation that
 * throws, are removed from the in-flight set, so the next caller computes afresh. The memo is
 * bounded and evicts the oldest entry. At most {@code maxInFlight} distinct computations run at
 * once.
 *
 * <p>A joining thread waits for the running computation of another thread. The computations
 * memoized here are CPU-only and bounded, never network or sleeping work.
 *
 * <p>Internal API, not part of the stable surface.
 *
 * @param <V> result type
 */
public final class SingleFlightMemo<V> {

  /** How a lookup was answered. */
  public enum Source {
    /** Served from a retained result. */
    HIT,
    /** Joined a computation already running. */
    JOINED,
    /** Computed by this call. */
    COMPUTED
  }

  /** Result of a lookup with the way it was answered. */
  public static final class Outcome<V> {
    private final V value;
    private final Source source;

    private Outcome(V value, Source source) {
      this.value = value;
      this.source = source;
    }

    /**
     * The result.
     *
     * @return value
     */
    public V getValue() {
      return value;
    }

    /**
     * How the lookup was answered.
     *
     * @return source
     */
    public Source getSource() {
      return source;
    }
  }

  /** Thrown when the memo already runs its limit of distinct computations. */
  public static final class BusyException extends RuntimeException {
    private final int maxInFlight;

    BusyException(int maxInFlight) {
      super("Too many concurrent verifications (limit " + maxInFlight + ").");
      this.maxInFlight = maxInFlight;
    }

    /**
     * The limit that was reached.
     *
     * @return most computations in flight
     */
    public int getMaxInFlight() {
      return maxInFlight;
    }
  }

  private final int maxEntries;
  private final int maxInFlight;
  private final Predicate<V> retain;
  private final Map<String, V> completed = new LinkedHashMap<>();
  private final Map<String, CompletableFuture<V>> inFlight = new HashMap<>();

  /**
   * Creates a memo.
   *
   * @param maxEntries most retained results, positive
   * @param maxInFlight most distinct computations at once, positive
   * @param retain tells which results are retained (successes)
   * @throws IllegalArgumentException if a limit is not positive
   */
  public SingleFlightMemo(int maxEntries, int maxInFlight, Predicate<V> retain) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be positive.");
    }
    if (maxInFlight <= 0) {
      throw new IllegalArgumentException("maxInFlight must be positive.");
    }
    this.maxEntries = maxEntries;
    this.maxInFlight = maxInFlight;
    this.retain = Objects.requireNonNull(retain, "retain cannot be null");
  }

  /**
   * Returns the retained result of a key, joins its running computation, or computes it.
   *
   * @param key complete key of everything the result depends on
   * @param compute computation, run by exactly one caller per key at a time
   * @return result and how it was answered
   * @throws BusyException if the limit of computations in flight is reached
   */
  public Outcome<V> get(String key, Supplier<V> compute) {
    CompletableFuture<V> mine;
    CompletableFuture<V> running;
    synchronized (this) {
      V done = completed.get(key);
      if (done != null) {
        return new Outcome<>(done, Source.HIT);
      }
      running = inFlight.get(key);
      if (running == null) {
        if (inFlight.size() >= maxInFlight) {
          throw new BusyException(maxInFlight);
        }
        mine = new CompletableFuture<>();
        inFlight.put(key, mine);
      } else {
        mine = null;
      }
    }

    if (mine == null) {
      try {
        return new Outcome<>(running.join(), Source.JOINED);
      } catch (CompletionException e) {
        // the computation of the other caller failed; this caller computes for itself
        if (e.getCause() instanceof RuntimeException || e.getCause() instanceof Error) {
          return get(key, compute);
        }
        throw e;
      }
    }

    V value;
    try {
      value = compute.get();
    } catch (RuntimeException | Error e) {
      synchronized (this) {
        inFlight.remove(key);
      }
      mine.completeExceptionally(e);
      throw e;
    }
    synchronized (this) {
      inFlight.remove(key);
      if (retain.test(value)) {
        if (completed.size() >= maxEntries) {
          Iterator<String> oldest = completed.keySet().iterator();
          oldest.next();
          oldest.remove();
        }
        completed.put(key, value);
      }
    }
    mine.complete(value);
    return new Outcome<>(value, Source.COMPUTED);
  }

  /**
   * Number of retained results.
   *
   * @return resident entries
   */
  public synchronized int size() {
    return completed.size();
  }

  /**
   * Number of computations currently running.
   *
   * @return distinct keys in flight
   */
  public synchronized int inFlightCount() {
    return inFlight.size();
  }
}
