
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
import org.unicitylabs.sdk.internal.OperationContext;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
              .post(RequestBody.create(
                      UnicityObjectMapper.JSON.writeValueAsString(envelope),
                      JsonRpcHttpTransport.MEDIA_TYPE_JSON));
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

      future.whenComplete((result, error) -> {
        if (future.isCancelled()) {
          cancelCall.run();
        }
        if (registration != null) {
          registration.remove();
        }
      });

      call.enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
          if (cancelledByUs.get() || (operation != null && operation.isCancelled())) {
            CancellationException cancelled = new CancellationException("Request cancelled.");
            cancelled.initCause(e);
            future.completeExceptionally(cancelled);
          } else if (operation != null && operation.isExpired()) {
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
            String bodyString = JsonRpcHttpTransport.this.readBounded(body);
            JsonNode envelope = JsonRpcHttpTransport.parseEnvelope(bodyString);

            if (!response.isSuccessful()) {
              // The service's own error survives any HTTP status; a body that is not a matching
              // envelope says nothing about the request.
              JsonRpcDataException rpcError = null;
              if (envelope != null && "2.0".equals(envelope.path("jsonrpc").asText(null))
                      && envelope.hasNonNull("error") && !envelope.has("result")
                      && requestId.equals(envelope.get("id"))) {
                rpcError = JsonRpcHttpTransport.errorOf(envelope.get("error"), true, false);
              }
              future.completeExceptionally(
                      new JsonRpcNetworkException(response.code(), bodyString, rpcError));
              return;
            }
            future.complete(JsonRpcHttpTransport.result(envelope, requestId));
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

  private static JsonNode parseEnvelope(String body) {
    try {
      JsonNode node = UnicityObjectMapper.JSON.readTree(body);
      return node != null && node.isObject() ? node : null;
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static JsonNode result(JsonNode envelope, JsonNode requestId)
          throws JsonRpcDataException {
    if (envelope == null) {
      throw new JsonRpcResponseException("JSON-RPC response is not a valid JSON object.");
    }
    if (!"2.0".equals(envelope.path("jsonrpc").asText(null))) {
      throw new JsonRpcResponseException(
              "Invalid JSON-RPC version: " + envelope.get("jsonrpc") + ".");
    }
    boolean hasResult = envelope.has("result");
    boolean hasError = envelope.hasNonNull("error");
    if (hasResult && hasError) {
      throw new JsonRpcResponseException(
              "JSON-RPC response must contain exactly one of result or error.");
    }
    JsonNode id = envelope.get("id");
    if (hasError) {
      // An error that answers no request of ours (a null id is what a server uses when it could
      // not read the id) is kept for callers that tolerate it, marked as not matched.
      boolean matched = requestId.equals(id);
      if (!matched && !(id == null || id.isNull())) {
        throw new JsonRpcResponseException("JSON-RPC response id mismatch: expected " + requestId
                + ", got " + id + ".");
      }
      throw errorOf(envelope.get("error"), matched, true);
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
  private static JsonRpcDataException errorOf(JsonNode error, boolean matched, boolean strict) {
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
            matched);
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
    if (timeout == null || timeout.compareTo(Duration.ofDays(1)) > 0) {
      return this.httpClient;
    }
    // zero means "no timeout" to OkHttp: never let an exhausted budget become unbounded
    Duration bounded = timeout.isZero() ? Duration.ofMillis(1) : timeout;
    return this.httpClient.newBuilder().callTimeout(bounded).readTimeout(bounded).build();
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
