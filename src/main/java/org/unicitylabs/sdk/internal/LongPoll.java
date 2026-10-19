package org.unicitylabs.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import org.unicitylabs.sdk.api.WaitOptions;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Wire rules and pacing of the optional long poll ({@code waitMs} in the request, {@code waitedMs}
 * in the answer).
 *
 * <p>The wait is a request to the aggregator, never an obligation: a server that does not know the
 * field ignores it and answers at once without an echo. The echo is unauthenticated timing
 * metadata, it never authenticates state and never extends a deadline.
 *
 * <p>Internal API, not part of the stable surface.
 */
public final class LongPoll {

  /** Pause that a long poll never undercuts, so an instant or zero echo cannot make a tight loop. */
  public static final long MIN_POLL_GAP_MILLIS = 10;

  private LongPoll() {
  }

  /**
   * Tells if a JSON value is a wire millisecond count.
   *
   * @param node candidate, may be null
   * @return true for a non-negative integer not above {@link WaitOptions#MAX_WAIT_MILLIS}
   */
  public static boolean isWireMillis(JsonNode node) {
    return node != null && node.isIntegralNumber() && node.canConvertToLong()
        && node.longValue() >= 0 && node.longValue() <= WaitOptions.MAX_WAIT_MILLIS;
  }

  /**
   * The {@code waitMs} of the next request: {@code floor(max(0, min(window, left - margin)))}.
   * {@code 0} means no wait fits and an ordinary immediate lookup is made.
   *
   * @param window preferred window
   * @param margin response margin
   * @param left time left of the budget
   * @return milliseconds to ask the aggregator to wait
   */
  public static long window(Duration window, Duration margin, Duration left) {
    long windowMillis = clampMillis(window);
    long leftMillis = clampMillis(left);
    long marginMillis = clampMillis(margin);
    long fit = Math.min(windowMillis, leftMillis - marginMillis);
    return Math.max(0, Math.min(fit, WaitOptions.MAX_WAIT_MILLIS));
  }

  /**
   * The pause after a pending answer to a poll that asked for a wait. An immediate new call is
   * allowed only for a valid positive echo, an answer that is pending (not an {@code unknown}
   * hint) and a call that locally took at least the polling interval; anything else sleeps the rest
   * of the interval, never less than a small floor, so that a missing, zero or hostile echo cannot
   * make a tight loop. A missing echo means the endpoint ignored the wait: the normal pause.
   *
   * @param intervalMillis polling interval
   * @param tookMillis local duration of the call
   * @param waitedMillis the echo, null if there was none
   * @param pending whether the answer was pending rather than an {@code unknown} hint
   * @return milliseconds to sleep, null for an immediate new call
   */
  public static Long pacedPause(long intervalMillis, long tookMillis, Long waitedMillis,
      boolean pending) {
    long paced = Math.max(intervalMillis, MIN_POLL_GAP_MILLIS);
    if (waitedMillis == null) {
      return paced;
    }
    if (pending && waitedMillis > 0 && tookMillis >= paced) {
      return null;
    }
    return pending ? Math.max(intervalMillis - tookMillis, MIN_POLL_GAP_MILLIS) : paced;
  }

  /**
   * Tells if an error is the answer of a batch endpoint whose strict parser does not know a request
   * field: the generic {@code -32602 / invalid_request / malformed_params}. The answer does not name
   * the field, so the match is on the generic class. An answer with {@code data.waitedMs} is not it:
   * the server understood the wait.
   *
   * @param code JSON-RPC error code
   * @param data the {@code error.data} member, may be null
   * @return true for exactly that class
   */
  public static boolean isMalformedParams(int code, JsonNode data) {
    if (code != -32602 || data == null || !data.isObject()) {
      return false;
    }
    return "invalid_request".equals(data.path("reason").asText(null))
        && "malformed_params".equals(data.path("detail").asText(null))
        && !data.has("waitedMs");
  }

  /**
   * Maps the result of a future, forwarding cancellation of the mapped future to the source (a plain
   * {@code thenApply} does not).
   *
   * @param source future of the underlying call
   * @param mapper result mapping
   * @param <T> source type
   * @param <R> result type
   * @return mapped future whose cancellation cancels {@code source}
   */
  public static <T, R> CompletableFuture<R> map(CompletableFuture<T> source,
      Function<T, R> mapper) {
    return map(source, mapper, null);
  }

  /**
   * Maps the result of a future like {@link #map(CompletableFuture, Function)}; a mapped result
   * that is ready after the budget of the operation ended is discarded as a timeout.
   *
   * @param source future of the underlying call
   * @param mapper result mapping (decoding)
   * @param operation operation whose budget governs the publication, may be null
   * @param <T> source type
   * @param <R> result type
   * @return mapped future whose cancellation cancels {@code source}
   */
  public static <T, R> CompletableFuture<R> map(CompletableFuture<T> source,
      Function<T, R> mapper, OperationContext operation) {
    CompletableFuture<R> result = new CompletableFuture<>();
    source.whenComplete((value, error) -> {
      if (error != null) {
        result.completeExceptionally(error);
        return;
      }
      try {
        R mapped = mapper.apply(value);
        if (operation != null && operation.isExpired()) {
          result.completeExceptionally(
              new java.util.concurrent.TimeoutException("Operation deadline expired."));
        } else {
          result.complete(mapped);
        }
      } catch (RuntimeException e) {
        result.completeExceptionally(e);
      }
    });
    result.whenComplete((value, error) -> {
      if (result.isCancelled()) {
        source.cancel(true);
      }
    });
    return result;
  }

  private static long clampMillis(Duration duration) {
    try {
      return duration.toMillis();
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE / 4;
    }
  }
}
