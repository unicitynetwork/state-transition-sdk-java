
package org.unicitylabs.sdk.api.jsonrpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.unicitylabs.sdk.api.RequestOptions;
import org.unicitylabs.sdk.api.WaitOptions;
import org.unicitylabs.sdk.internal.InclusionProofWaiter;
import org.unicitylabs.sdk.internal.OperationContext;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * JSON-RPC HTTP service.
 */
public class JsonRpcHttpTransport {

  /** Default maximum response body size in bytes. */
  public static final int DEFAULT_MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

  /** A timeout this close to the end of the budget is the deadline of the operation. */
  private static final Duration DEADLINE_SLACK = Duration.ofMillis(100);

  /** The most seconds of a Retry-After the transport reports; a larger value saturates here. */
  public static final long MAX_RETRY_AFTER_SECONDS = 60;
  private static final MediaType MEDIA_TYPE_JSON = MediaType.get("application/json; charset=utf-8");

  private static final OkHttpClient DEFAULT_HTTP_CLIENT = new OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .build();

  private final String url;
  private final int maxResponseBytes;
  private final OkHttpClient httpClient;
  private final Supplier<JsonNode> idSupplier;

  /**
   * JSON-RPC HTTP service constructor.
   *
   * @param url service URL
   */
  public JsonRpcHttpTransport(String url) {
    this(url, JsonRpcHttpTransport.DEFAULT_MAX_RESPONSE_BYTES);
  }

  /**
   * JSON-RPC HTTP service constructor.
   *
   * @param url service URL
   * @param maxResponseBytes maximum response body size in bytes
   */
  public JsonRpcHttpTransport(String url, int maxResponseBytes) {
    this(url, JsonRpcHttpTransport.DEFAULT_HTTP_CLIENT, maxResponseBytes);
  }

  /**
   * JSON-RPC HTTP service constructor with a caller-supplied HTTP client, to share a single
   * connection and thread pool across transports.
   *
   * @param url service URL
   * @param httpClient OkHttp client to use
   */
  public JsonRpcHttpTransport(String url, OkHttpClient httpClient) {
    this(url, httpClient, JsonRpcHttpTransport.DEFAULT_MAX_RESPONSE_BYTES);
  }

  /**
   * JSON-RPC HTTP service constructor with a caller-supplied HTTP client, to share a single
   * connection and thread pool across transports. Redirect following is always disabled on the
   * transport's client (via {@link OkHttpClient#newBuilder()}, which shares the supplied client's
   * connection pool and dispatcher) so authentication headers are never replayed to a redirect
   * target, regardless of the caller's redirect policy.
   *
   * @param url service URL
   * @param httpClient OkHttp client to use
   * @param maxResponseBytes maximum response body size in bytes
   */
  public JsonRpcHttpTransport(String url, OkHttpClient httpClient, int maxResponseBytes) {
    this(url, httpClient, maxResponseBytes, () -> TextNode.valueOf(UUID.randomUUID().toString()));
  }

  /**
   * Constructor with an explicit request id source, for tests that replay recorded exchanges.
   */
  JsonRpcHttpTransport(String url, OkHttpClient httpClient, int maxResponseBytes,
      Supplier<JsonNode> idSupplier) {
    this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier cannot be null");
    this.url = Objects.requireNonNull(url, "url cannot be null");
    if (maxResponseBytes <= 0) {
      throw new IllegalArgumentException(
              "maxResponseBytes must be positive, got " + maxResponseBytes + ".");
    }
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null")
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            // A JSON-RPC POST is never replayed by the transport: a replayed submission has an
            // uncertain outcome, and a replayed read would hide an overload from the waiter's
            // policy. The request body is one-shot, which stops OkHttp's replay of a sent request
            // (a 408, a dropped connection); the 503 replay on Retry-After 0 is stopped by hiding
            // that header, see below. Trying another route (IPv6 then IPv4) before anything was
            // sent stays possible, so retryOnConnectionFailure is left as the caller set it.
            .addNetworkInterceptor(JsonRpcHttpTransport::hideRetryAfter)
            .build();
    this.maxResponseBytes = maxResponseBytes;
  }

  /**
   * Send a JSON-RPC request.
   *
   * @param <T>        expected result type
   * @param method     JSON-RPC method
   * @param params     JSON-RPC parameters
   * @param resultType expected result type
   * @return future with result
   */
  public <T> CompletableFuture<T> request(String method, Object params, Class<T> resultType) {
    return this.request(method, params, resultType, Map.of());
  }

  /**
   * Send a JSON-RPC request with additional headers.
   *
   * @param <T>        expected result type
   * @param method     JSON-RPC method
   * @param params     JSON-RPC parameters
   * @param resultType expected result type
   * @param headers    additional HTTP headers
   * @return future with result
   */
  public <T> CompletableFuture<T> request(
          String method,
          Object params,
          Class<T> resultType,
          Map<String, List<String>> headers
  ) {
    Objects.requireNonNull(resultType, "resultType cannot be null");
    return this.call(method, params, headers, RequestOptions.none()).thenApply(result -> {
      try {
        return UnicityObjectMapper.JSON.treeToValue(result, resultType);
      } catch (JsonProcessingException e) {
        throw new JsonRpcResponseException("Malformed result of " + method + ": "
                + e.getOriginalMessage(), e);
      }
    });
  }

  /**
   * Send a JSON-RPC request and return the raw result.
   *
   * <p>The response is validated before anything is interpreted: a bounded body, a JSON object
   * with version {@code 2.0}, exactly one of result and error, and the id of this request (an
   * error whose id is null is kept, marked as not matched). Failures: {@link
   * JsonRpcDataException} for the service's error, {@link JsonRpcNetworkException} for an HTTP
   * status other than success, {@link JsonRpcResponseException} for a response that is not valid,
   * {@link IOException} for transport failures, {@link TimeoutException} when the budget of the
   * operation ended and {@link CancellationException} when it was cancelled. Cancelling the
   * returned future, or the operation, cancels the HTTP call.
   *
   * @param method  JSON-RPC method
   * @param params  JSON-RPC parameters
   * @param headers additional HTTP headers
   * @param options call timeout and operation; the wait and the status hint are part of
   *                {@code params}, the wait only sizes the default timeout
   * @return future with the result node
   */
  public CompletableFuture<JsonNode> call(
          String method,
          Object params,
          Map<String, List<String>> headers,
          RequestOptions options
  ) {
    Objects.requireNonNull(method, "method cannot be null");
    Objects.requireNonNull(headers, "headers cannot be null");
    Objects.requireNonNull(options, "options cannot be null");
    OperationContext operation = options.getOperation();

    if (operation != null) {
      if (operation.isCancelled()) {
        return CompletableFuture.failedFuture(new CancellationException("Operation cancelled."));
      }
      if (operation.isExpired()) {
        return CompletableFuture.failedFuture(new TimeoutException("Operation deadline expired."));
      }
    }

    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    try {
      JsonNode requestId = this.idSupplier.get();
      ObjectNode envelope = UnicityObjectMapper.JSON.createObjectNode();
      envelope.put("jsonrpc", "2.0");
      envelope.set("id", requestId);
      envelope.put("method", method);
      envelope.set("params", UnicityObjectMapper.JSON.valueToTree(params));

      Request.Builder requestBuilder = new Request.Builder()
              .url(this.url)
              .post(JsonRpcHttpTransport.oneShot(RequestBody.create(
                      UnicityObjectMapper.JSON.writeValueAsString(envelope),
                      JsonRpcHttpTransport.MEDIA_TYPE_JSON)));
      headers.forEach((header, values) ->
              values.forEach(value -> requestBuilder.addHeader(header, value)));

      OkHttpClient client = this.clientFor(options);
      Call call = client.newCall(requestBuilder.build());
      // OkHttp also cancels a call whose own timeout expired: only our own cancellation counts as one
      AtomicBoolean cancelledByUs = new AtomicBoolean();
      Runnable cancelCall = () -> {
        cancelledByUs.set(true);
        call.cancel();
      };
      OperationContext.Registration registration =
              operation == null ? null : operation.onCancel(cancelCall);

      // The budget governs the exposed request to its completion, not only the HTTP exchange:
      // OkHttp's own timeout ends with the response body, an independent timer does not.
      Runnable cancelTimer = null;
      if (operation != null) {
        Duration left = operation.remaining();
        if (left.compareTo(Duration.ofDays(1)) < 0) {
          cancelTimer = InclusionProofWaiter.scheduleOnSharedTimer(() ->
              future.completeExceptionally(new TimeoutException("Operation deadline expired.")),
              Math.max(0, left.toMillis()));
        }
      }
      Runnable releaseTimer = cancelTimer;
      future.whenComplete((result, error) -> {
        if (future.isCancelled() || error instanceof TimeoutException) {
          cancelCall.run();
        }
        if (registration != null) {
          registration.remove();
        }
        if (releaseTimer != null) {
          releaseTimer.run();
        }
      });

      call.enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
          if (cancelledByUs.get() || (operation != null && operation.isCancelled())) {
            CancellationException cancelled = new CancellationException("Request cancelled.");
            cancelled.initCause(e);
            future.completeExceptionally(cancelled);
          } else if (operation != null && (operation.isExpired()
                  || (e instanceof java.io.InterruptedIOException
                  && operation.remaining().compareTo(DEADLINE_SLACK) < 0))) {
            // OkHttp's own timer, started from the remaining budget, may fire a hair before ours
            future.completeExceptionally(
                    (TimeoutException) new TimeoutException("Operation deadline expired.")
                            .initCause(e));
          } else {
            future.completeExceptionally(e);
          }
        }

        @Override
        public void onResponse(Call call, Response response) {
          try (ResponseBody body = response.body()) {
            if (operation != null && operation.isExpired()) {
              // late: the budget ended while the response was in flight
              future.completeExceptionally(new TimeoutException("Operation deadline expired."));
              return;
            }
            // The HTTP status of a failure is kept whatever the body is: a proxy page, a body that is
            // not JSON or one over the size limit must not turn a 502 into a response failure.
            String bodyString = response.isSuccessful()
                    ? JsonRpcHttpTransport.this.readBounded(body)
                    : JsonRpcHttpTransport.this.readErrorBody(body);
            // A cancellation or a deadline that came while the body was read is the failure, never a
            // retryable HTTP status built from whatever was left of the body.
            if (cancelledByUs.get() || (operation != null && operation.isCancelled())) {
              future.completeExceptionally(new CancellationException("Request cancelled."));
              return;
            }
            if (operation != null && operation.isExpired()) {
              future.completeExceptionally(new TimeoutException("Operation deadline expired."));
              return;
            }
            JsonNode envelope = JsonRpcHttpTransport.parseEnvelope(bodyString);

            if (!response.isSuccessful()) {
              // The service's own error survives any HTTP status; a body that is not a matching
              // envelope says nothing about the request.
              JsonRpcDataException rpcError = null;
              if (envelope != null && "2.0".equals(envelope.path("jsonrpc").asText(null))
                      && envelope.has("error") && envelope.get("error").isObject() && !envelope.has("result")
                      && requestId.equals(envelope.get("id"))) {
                rpcError = JsonRpcHttpTransport.errorOf(envelope.get("error"), true, false, null);
              }
              future.completeExceptionally(
                      new JsonRpcNetworkException(response.code(), bodyString, rpcError,
                              JsonRpcHttpTransport.retryAfterOf(response)));
              return;
            }
            JsonNode result = JsonRpcHttpTransport.result(envelope, requestId,
                    JsonRpcHttpTransport.retryAfterOf(response));
            if (operation != null && operation.isExpired()) {
              future.completeExceptionally(new TimeoutException("Operation deadline expired."));
              return;
            }
            future.complete(result);
          } catch (Exception e) {
            future.completeExceptionally(e);
          }
        }
      });
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
    return future;
  }

  /** Header the transport keeps the server's Retry-After in, out of OkHttp's sight. */
  static final String RETRY_AFTER_KEPT = "X-Unicity-Retry-After";

  /**
   * Moves Retry-After out of the response before OkHttp's retry logic reads it. OkHttp replays the
   * request on a 503 whose Retry-After is 0 (and fails the call on a number that does not fit an
   * int); the transport never replays, and keeps the server's value in another header for the
   * typed failure. Whatever a server sends as the kept header itself is dropped.
   */
  private static okhttp3.Response hideRetryAfter(okhttp3.Interceptor.Chain chain)
          throws IOException {
    okhttp3.Response response = chain.proceed(chain.request());
    String header = response.header("Retry-After");
    okhttp3.Response.Builder builder = response.newBuilder()
            .removeHeader("Retry-After").removeHeader(RETRY_AFTER_KEPT);
    if (header != null) {
      builder.header(RETRY_AFTER_KEPT, header);
    }
    return builder.build();
  }

  /** The Retry-After the server sent, as seconds, from the kept header. */
  private static Long retryAfterOf(Response response) {
    return retryAfter(response.header(RETRY_AFTER_KEPT));
  }

  /** A body that is sent once and never again: OkHttp does not replay a one-shot request. */
  private static RequestBody oneShot(RequestBody body) {
    return new RequestBody() {
      @Override
      public okhttp3.MediaType contentType() {
        return body.contentType();
      }

      @Override
      public long contentLength() throws IOException {
        return body.contentLength();
      }

      @Override
      public void writeTo(okio.BufferedSink sink) throws IOException {
        body.writeTo(sink);
      }

      @Override
      public boolean isOneShot() {
        return true;
      }
    };
  }

  private static final java.util.List<java.time.format.DateTimeFormatter> HTTP_DATES =
          java.util.List.of(
                  java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
                  java.time.format.DateTimeFormatter.ofPattern(
                          "EEEE, dd-MMM-yy HH:mm:ss 'GMT'", java.util.Locale.US)
                          .withZone(java.time.ZoneOffset.UTC),
                  java.time.format.DateTimeFormatter.ofPattern(
                          "EEE MMM ppd HH:mm:ss yyyy", java.util.Locale.US)
                          .withZone(java.time.ZoneOffset.UTC));

  /**
   * The Retry-After header as seconds (RFC 9110, 10.2.3): delay-seconds, or an HTTP-date as the
   * time from now to it. Seconds of any length, and a date further away, saturate at
   * {@link #MAX_RETRY_AFTER_SECONDS}; a date in the past is 0. Anything else is null.
   */
  static Long retryAfter(String header) {
    return retryAfter(header, Instant.now());
  }

  /**
   * As {@link #retryAfter(String)} with the time now given, so the date form can be tested.
   *
   * @param header the header value, may be null
   * @param now the time a date is measured from
   * @return seconds, null if the header is absent or in no valid form
   */
  static Long retryAfter(String header, Instant now) {
    if (header == null) {
      return null;
    }
    String value = header.trim();
    if (value.isEmpty()) {
      return null;
    }
    if (value.chars().allMatch(c -> c >= '0' && c <= '9')) {
      long seconds = 0;
      for (int i = 0; i < value.length(); i++) {
        seconds = Math.min(MAX_RETRY_AFTER_SECONDS, seconds * 10 + (value.charAt(i) - '0'));
      }
      return seconds;
    }
    for (java.time.format.DateTimeFormatter format : HTTP_DATES) {
      try {
        Instant date = Instant.from(format.parse(value));
        long millis = date.toEpochMilli() - now.toEpochMilli();
        if (millis <= 0) {
          return 0L;
        }
        return Math.min(MAX_RETRY_AFTER_SECONDS, (millis + 999) / 1000);
      } catch (java.time.DateTimeException e) {
        // another form
      }
    }
    return null;
  }

  /** Parses the whole body as one JSON object; trailing data after the value is not a valid response. */
  private static JsonNode parseEnvelope(String body) {
    try (com.fasterxml.jackson.core.JsonParser parser =
                 UnicityObjectMapper.JSON.getFactory().createParser(body)) {
      JsonNode node = UnicityObjectMapper.JSON.readTree(parser);
      if (node == null || !node.isObject() || parser.nextToken() != null) {
        return null;
      }
      return node;
    } catch (IOException e) {
      return null;
    }
  }

  private static JsonNode result(JsonNode envelope, JsonNode requestId, Long retryAfter)
          throws JsonRpcDataException {
    if (envelope == null) {
      throw new JsonRpcResponseException("JSON-RPC response is not a valid JSON object.");
    }
    if (!"2.0".equals(envelope.path("jsonrpc").asText(null))) {
      throw new JsonRpcResponseException(
              "Invalid JSON-RPC version: " + envelope.get("jsonrpc") + ".");
    }
    boolean hasResult = envelope.has("result");
    boolean hasError = envelope.has("error");
    if (hasError && !envelope.get("error").isObject()) {
      throw new JsonRpcResponseException("JSON-RPC error must be an object.");
    }
    if (hasResult && hasError) {
      throw new JsonRpcResponseException(
              "JSON-RPC response must contain exactly one of result or error.");
    }
    JsonNode id = envelope.get("id");
    if (hasError) {
      // An error that answers no request of ours (a null id is what a server uses when it could
      // not read the id) is kept for callers that tolerate it, marked as not matched.
      // A missing id is no such answer: only an explicit null is.
      if (!envelope.has("id")) {
        throw new JsonRpcResponseException("JSON-RPC error response has no id.");
      }
      boolean matched = requestId.equals(id);
      if (!matched && !id.isNull()) {
        throw new JsonRpcResponseException("JSON-RPC response id mismatch: expected " + requestId
                + ", got " + id + ".");
      }
      throw errorOf(envelope.get("error"), matched, true, retryAfter);
    }
    if (!requestId.equals(id)) {
      throw new JsonRpcResponseException("JSON-RPC response id mismatch: expected " + requestId
              + ", got " + id + ".");
    }
    if (!hasResult) {
      throw new JsonRpcResponseException(
              "JSON-RPC response must contain exactly one of result or error.");
    }
    return envelope.get("result");
  }

  /** Builds the typed error of an error object; a malformed object is a response failure. */
  private static JsonRpcDataException errorOf(JsonNode error, boolean matched, boolean strict,
          Long retryAfter) {
    JsonNode code = error.get("code");
    JsonNode message = error.get("message");
    if (code == null || !code.isIntegralNumber() || !code.canConvertToInt()
            || message == null || !message.isTextual()) {
      if (strict) {
        throw new JsonRpcResponseException(
                "JSON-RPC error object needs an integer code and a message.");
      }
      return null;
    }
    return new JsonRpcDataException(code.intValue(), message.textValue(), error.get("data"),
            matched, retryAfter);
  }

  /**
   * The HTTP client of one call. The shared client keeps its connection pool and dispatcher; a call
   * with its own timeout gets a derived client (a held request outlives the default read timeout).
   */
  private OkHttpClient clientFor(RequestOptions options) {
    Duration timeout = options.getCallTimeout();
    Long wait = options.getWaitMillis();
    if (timeout == null && wait != null && wait > 0) {
      timeout = Duration.ofMillis(wait).plus(WaitOptions.DEFAULT_MARGIN);
    }
    OperationContext operation = options.getOperation();
    if (operation != null) {
      Duration remaining = operation.remaining();
      if (timeout == null || remaining.compareTo(timeout) < 0) {
        timeout = remaining;
      }
    }
    if (timeout == null) {
      // no timeout asked for: the caller's client keeps its own, shorter ones; a disabled or longer
      // one is brought down to the limit, so no call is unbounded
      long limit = RequestOptions.MAX_CALL_TIMEOUT.toMillis();
      int call = this.httpClient.callTimeoutMillis();
      int read = this.httpClient.readTimeoutMillis();
      if (call > 0 && call <= limit && read > 0 && read <= limit) {
        return this.httpClient;
      }
      OkHttpClient.Builder builder = this.httpClient.newBuilder();
      if (call <= 0 || call > limit) {
        builder.callTimeout(RequestOptions.MAX_CALL_TIMEOUT);
      }
      if (read <= 0 || read > limit) {
        builder.readTimeout(RequestOptions.MAX_CALL_TIMEOUT);
      }
      return builder.build();
    }
    // no call outlives the limit: a time left in a long budget is clipped to it
    if (timeout.compareTo(RequestOptions.MAX_CALL_TIMEOUT) > 0) {
      timeout = RequestOptions.MAX_CALL_TIMEOUT;
    }
    // zero means "no timeout" to OkHttp: never let an exhausted budget become unbounded
    Duration bounded = timeout.isZero() ? Duration.ofMillis(1) : timeout;
    return this.httpClient.newBuilder().callTimeout(bounded).readTimeout(bounded).build();
  }

  /**
   * The body of a non-success response, for the message of the failure: a body over the limit or
   * one that cannot be read is replaced by a note, so the status is always reported. Closing the
   * response discards the connection of an abandoned body.
   */
  private String readErrorBody(ResponseBody body) {
    try {
      return this.readBounded(body);
    } catch (JsonRpcResponseException e) {
      return "(response body exceeds the maximum allowed size)";
    } catch (IOException e) {
      return "(response body unreadable: " + e.getMessage() + ")";
    }
  }

  /**
   * Read the response body as a string, rejecting bodies larger than the configured limit before
   * buffering the whole payload.
   */
  private String readBounded(ResponseBody body) throws IOException {
    if (body == null) {
      return "";
    }

    try (InputStream in = body.byteStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      long total = 0;
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > this.maxResponseBytes) {
          throw new JsonRpcResponseException("JSON-RPC response exceeds the maximum allowed size.");
        }
        out.write(buffer, 0, read);
      }

      // ByteArrayOutputStream.toString(Charset) is post-API-31; construct the String directly.
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
