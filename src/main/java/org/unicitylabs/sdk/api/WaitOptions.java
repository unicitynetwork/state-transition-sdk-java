package org.unicitylabs.sdk.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Options of waiting for an inclusion proof after a submission.
 *
 * <p>By default the wait asks the aggregator to hold each request (long poll) and falls back to
 * interval polling when the aggregator gives no wait echo. The preferred window of one long poll is
 * 20 s and a margin of 2 s is kept between the end of a server wait and the end of the time left;
 * both are clipped by the remaining budget and the per-call timeout. {@link #longPoll(boolean)
 * longPoll(false)} gives the legacy interval polling.
 */
public final class WaitOptions {

  /** Default total time of a wait. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  /** Default interval of the interval polling fallback. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);
  /** Default preferred window of one long poll. */
  public static final Duration DEFAULT_WINDOW = Duration.ofSeconds(20);
  /** Default margin between the end of a server wait and the end of the time left. */
  public static final Duration DEFAULT_MARGIN = Duration.ofSeconds(2);
  /** Largest wait the wire carries, in milliseconds (2^53 - 1). */
  public static final long MAX_WAIT_MILLIS = 9007199254740991L;

  private final Duration timeout;
  private final Duration interval;
  private final boolean longPoll;
  private final Duration window;
  private final Duration margin;

  private WaitOptions(Duration timeout, Duration interval, boolean longPoll, Duration window,
      Duration margin) {
    this.timeout = timeout;
    this.interval = interval;
    this.longPoll = longPoll;
    this.window = window;
    this.margin = margin;
  }

  /**
   * Creates the default options: long poll, 30 s total, 1 s fallback interval.
   *
   * @return default options
   */
  public static WaitOptions defaults() {
    return new WaitOptions(DEFAULT_TIMEOUT, DEFAULT_INTERVAL, true, DEFAULT_WINDOW,
        DEFAULT_MARGIN);
  }

  /**
   * Sets the total time of the wait.
   *
   * @param timeout positive duration
   * @return new options
   */
  public WaitOptions timeout(Duration timeout) {
    return new WaitOptions(positive(timeout, "timeout"), interval, longPoll, window, margin);
  }

  /**
   * Sets the interval of the polling fallback.
   *
   * @param interval positive duration
   * @return new options
   */
  public WaitOptions interval(Duration interval) {
    return new WaitOptions(timeout, positive(interval, "interval"), longPoll, window, margin);
  }

  /**
   * Switches the long poll on or off.
   *
   * @param longPoll false for the legacy interval polling
   * @return new options
   */
  public WaitOptions longPoll(boolean longPoll) {
    return new WaitOptions(timeout, interval, longPoll, window, margin);
  }

  /**
   * Sets the preferred window of one long poll.
   *
   * @param window positive duration, at most {@link #MAX_WAIT_MILLIS} milliseconds
   * @return new options
   */
  public WaitOptions window(Duration window) {
    return new WaitOptions(timeout, interval, longPoll, bounded(window, "window"), margin);
  }

  /**
   * Sets the response margin kept between the end of a server wait and the end of the time left.
   *
   * @param margin non-negative duration
   * @return new options
   */
  public WaitOptions margin(Duration margin) {
    Objects.requireNonNull(margin, "margin cannot be null");
    if (margin.isNegative()) {
      throw new IllegalArgumentException("margin must not be negative");
    }
    return new WaitOptions(timeout, interval, longPoll, window, margin);
  }

  /**
   * Total time of the wait.
   *
   * @return timeout
   */
  public Duration getTimeout() {
    return timeout;
  }

  /**
   * Interval of the polling fallback.
   *
   * @return interval
   */
  public Duration getInterval() {
    return interval;
  }

  /**
   * Tells if the long poll is asked for.
   *
   * @return true for long poll
   */
  public boolean isLongPoll() {
    return longPoll;
  }

  /**
   * Preferred window of one long poll.
   *
   * @return window
   */
  public Duration getWindow() {
    return window;
  }

  /**
   * Response margin.
   *
   * @return margin
   */
  public Duration getMargin() {
    return margin;
  }

  private static Duration positive(Duration value, String name) {
    Objects.requireNonNull(value, name + " cannot be null");
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static Duration bounded(Duration value, String name) {
    positive(value, name);
    if (value.compareTo(Duration.ofMillis(MAX_WAIT_MILLIS)) > 0) {
      throw new IllegalArgumentException(name + " is above the largest wait of the wire");
    }
    return value;
  }
}
