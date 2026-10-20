package org.unicitylabs.sdk.api.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.RequestOptions;
import org.unicitylabs.sdk.internal.OperationContext;

/** Envelope validation, error typing, bounded bodies and real-call cancellation of the transport. */
class JsonRpcHttpTransportTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private MockWebServer server;
  private OkHttpClient http;
  private JsonRpcHttpTransport transport;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.start();
    http = new OkHttpClient();
    transport = TestTransports.withIds(server.url("/").toString(), http, 1024, () -> IntNode.valueOf(7));
  }

  @AfterEach
  void stop() throws Exception {
    try {
      server.shutdown();
    } catch (java.io.IOException e) {
      // a response that is still held back when the test ends
    }
    http.dispatcher().executorService().shutdown();
  }

  private JsonRpcHttpTransport transport(int maxBytes) {
    return TestTransports.withIds(server.url("/").toString(), http, maxBytes, () -> IntNode.valueOf(7));
  }

  private Throwable failure(CompletableFuture<?> future) {
    try {
      future.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      return e.getCause();
    } catch (CancellationException e) {
      return e;
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    throw new AssertionError("the future did not fail");
  }

  private CompletableFuture<JsonNode> call() {
    return transport.call("m", Map.of("a", 1), Map.of(), RequestOptions.none());
  }

  @Test
  void aResponseBodyThatFinishesAfterTheBudgetCannotPublishSuccess() throws Exception {
    java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    OkHttpClient pausing = http.newBuilder().addInterceptor(chain -> {
      okhttp3.Response response = chain.proceed(chain.request());
      okhttp3.ResponseBody original = response.body();
      okio.BufferedSource source = okio.Okio.buffer(new okio.ForwardingSource(original.source()) {
        @Override
        public long read(okio.Buffer sink, long count) throws java.io.IOException {
          long n = super.read(sink, count);
          if (n == -1) {
            read.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return n;
        }
      });
      return response.newBuilder().body(new okhttp3.ResponseBody() {
        @Override public okhttp3.MediaType contentType() { return original.contentType(); }
        @Override public long contentLength() { return original.contentLength(); }
        @Override public okio.BufferedSource source() { return source; }
      }).build();
    }).build();
    JsonRpcHttpTransport tested = TestTransports.withIds(server.url("/").toString(),
        pausing, 1024, () -> IntNode.valueOf(7));
    server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":7}"));
    OperationContext operation = OperationContext.withBudget(Duration.ofMillis(500));
    CompletableFuture<JsonNode> future = tested.call("m", Map.of(), Map.of(),
        RequestOptions.none().withOperation(operation));
    try {
      assertTrue(read.await(3, TimeUnit.SECONDS), "the response was fully read");
      Awaitility.await().atMost(Duration.ofSeconds(2)).until(operation::isExpired);
    } finally {
      release.countDown();
    }
    assertInstanceOf(TimeoutException.class, failure(future));
  }

  @Test
  void aSuccessfulResponseThatArrivesAfterTheBudgetIsDiscarded() {
    AtomicLong nanos = new AtomicLong();
    OperationContext operation = OperationContext.withBudget(Duration.ofSeconds(1), nanos::get);
    OkHttpClient advancing = http.newBuilder().addInterceptor(chain -> {
      okhttp3.Response response = chain.proceed(chain.request());
      nanos.set(TimeUnit.SECONDS.toNanos(2));
      return response;
    }).build();
    JsonRpcHttpTransport tested = TestTransports.withIds(server.url("/").toString(),
        advancing, 1024, () -> IntNode.valueOf(7));
    server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":7}"));
    assertInstanceOf(TimeoutException.class, failure(tested.call("m", Map.of(), Map.of(),
        RequestOptions.none().withOperation(operation))));
  }

  @Test
  void sendsTheEnvelopeAndReturnsTheResult() throws Exception {
    server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":{\"x\":1},\"id\":7}"));
    assertEquals(1, call().get(5, TimeUnit.SECONDS).get("x").asInt());
    RecordedRequest request = server.takeRequest();
    JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
    assertEquals("2.0", body.get("jsonrpc").asText());
    assertEquals(7, body.get("id").asInt());
    assertEquals("m", body.get("method").asText());
    assertEquals(1, body.get("params").get("a").asInt());
  }

  @Test
  void aWrongIdIsAResponseFailureAlsoOnErrors() {
    server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":8}"));
    assertInstanceOf(JsonRpcResponseException.class, failure(call()));
    server.enqueue(new MockResponse().setBody(
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"x\"},\"id\":8}"));
    assertInstanceOf(JsonRpcResponseException.class, failure(call()));
  }

  @Test
  void anErrorWithANullIdIsKeptAndMarkedNotMatched() {
    server.enqueue(new MockResponse().setBody(
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"parse\"},\"id\":null}"));
    JsonRpcDataException e = (JsonRpcDataException) failure(call());
    assertEquals(-32700, e.getCode());
    assertFalse(e.isIdMatched());
  }

  @Test
  void aMatchingErrorKeepsCodeMessageAndData() {
    server.enqueue(new MockResponse().setBody(
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32008,\"message\":\"m\",\"data\":{\"waitedMs\":5}},\"id\":7}"));
    JsonRpcDataException e = (JsonRpcDataException) failure(call());
    assertEquals(-32008, e.getCode());
    assertEquals("m", e.getErrorMessage());
    assertEquals(5, e.getData().get("waitedMs").asInt());
    assertTrue(e.isIdMatched());
  }

  @Test
  void invalidEnvelopesAreResponseFailures() {
    String[] bodies = {
        "not json",
        "[1]",
        "{\"jsonrpc\":\"1.0\",\"result\":1,\"id\":7}",
        "{\"result\":1,\"id\":7}",
        "{\"jsonrpc\":\"2.0\",\"result\":1,\"error\":{\"code\":1,\"message\":\"m\"},\"id\":7}",
        "{\"jsonrpc\":\"2.0\",\"id\":7}",
        "{\"jsonrpc\":\"2.0\",\"result\":1,\"error\":null,\"id\":7}",
        "{\"jsonrpc\":\"2.0\",\"error\":null,\"id\":7}",
        "{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":7} {}",
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":\"x\",\"message\":\"m\"},\"id\":7}",
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":1},\"id\":7}",
    };
    for (String body : bodies) {
      server.enqueue(new MockResponse().setBody(body));
      assertInstanceOf(JsonRpcResponseException.class, failure(call()), body);
    }
  }

  @Test
  void anOversizeBodyIsRejectedBeforeItIsInterpreted() {
    server.enqueue(new MockResponse().setBody(
        "{\"jsonrpc\":\"2.0\",\"result\":\"" + "a".repeat(2048) + "\",\"id\":7}"));
    JsonRpcHttpTransport small = transport(1024);
    Throwable e = failure(small.call("m", Map.of(), Map.of(), RequestOptions.none()));
    assertInstanceOf(JsonRpcResponseException.class, e);
    assertTrue(e.getMessage().contains("maximum allowed size"));
  }

  @Test
  void aNon2xxStatusKeepsTheServicesOwnErrorOnlyForAMatchingEnvelope() {
    server.enqueue(new MockResponse().setResponseCode(404).setBody(
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32020,\"message\":\"none\"},\"id\":7}"));
    JsonRpcNetworkException matching = (JsonRpcNetworkException) failure(call());
    assertEquals(404, matching.getStatus());
    assertEquals(-32020, matching.getRpcError().getCode());

    server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"));
    assertNull(((JsonRpcNetworkException) failure(call())).getRpcError());

    server.enqueue(new MockResponse().setResponseCode(429).setBody(
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32006,\"message\":\"busy\"},\"id\":9}"));
    assertNull(((JsonRpcNetworkException) failure(call())).getRpcError());
  }

  @Test
  void redirectsAreNotFollowed() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")));
    JsonRpcNetworkException e = (JsonRpcNetworkException) failure(call());
    assertEquals(302, e.getStatus());
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void cancellingTheFutureCancelsTheInFlightHttpCall() throws Exception {
    server.enqueue(new MockResponse().setHeadersDelay(10, TimeUnit.SECONDS).setBody("{}"));
    CompletableFuture<JsonNode> future = call();
    server.takeRequest(5, TimeUnit.SECONDS);
    Awaitility.await().atMost(Duration.ofSeconds(5))
        .until(() -> http.dispatcher().runningCallsCount() == 1);
    future.cancel(true);
    Awaitility.await().atMost(Duration.ofSeconds(5))
        .until(() -> http.dispatcher().runningCallsCount() == 0);
  }

  @Test
  void cancellingTheOperationCancelsTheCallAndFailsTheFuture() throws Exception {
    server.enqueue(new MockResponse().setHeadersDelay(10, TimeUnit.SECONDS).setBody("{}"));
    OperationContext operation = OperationContext.unbounded();
    CompletableFuture<JsonNode> future = transport.call("m", Map.of(), Map.of(),
        RequestOptions.none().withOperation(operation));
    server.takeRequest(5, TimeUnit.SECONDS);
    operation.cancel();
    assertInstanceOf(CancellationException.class, failure(future));
    Awaitility.await().atMost(Duration.ofSeconds(5))
        .until(() -> http.dispatcher().runningCallsCount() == 0);
  }

  @Test
  void anAlreadyCancelledOperationSendsNothing() throws Exception {
    OperationContext operation = OperationContext.unbounded();
    operation.cancel();
    assertInstanceOf(CancellationException.class, failure(transport.call("m", Map.of(), Map.of(),
        RequestOptions.none().withOperation(operation))));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void anExpiredBudgetSendsNothingAndAnExhaustedOneEndsTheCall() throws Exception {
    OperationContext expired = OperationContext.withBudget(Duration.ZERO);
    assertInstanceOf(TimeoutException.class, failure(transport.call("m", Map.of(), Map.of(),
        RequestOptions.none().withOperation(expired))));
    assertEquals(0, server.getRequestCount());

    // the budget is spent in fake time while the request is held; the call's own timer then ends it
    AtomicLong nanos = new AtomicLong(1_000_000_000L);
    OperationContext budget = OperationContext.withBudget(Duration.ofSeconds(1), nanos::get);
    server.enqueue(new MockResponse().setHeadersDelay(10, TimeUnit.SECONDS).setBody("{}"));
    CompletableFuture<JsonNode> held = transport.call("m", Map.of(), Map.of(),
        RequestOptions.none().withOperation(budget));
    server.takeRequest(5, TimeUnit.SECONDS);
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
    assertInstanceOf(TimeoutException.class, failure(held));
  }

  @Test
  void aCallTimeoutOverridesTheSharedClientsShorterReadTimeout() throws Exception {
    OkHttpClient shortRead = http.newBuilder().readTimeout(Duration.ofMillis(100)).build();
    JsonRpcHttpTransport t = TestTransports.withIds(server.url("/").toString(), shortRead, 1024,
        () -> IntNode.valueOf(7));
    server.enqueue(new MockResponse().setHeadersDelay(400, TimeUnit.MILLISECONDS)
        .setBody("{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":7}"));
    assertTrue(t.call("m", Map.of(), Map.of(),
        RequestOptions.none().withCallTimeout(Duration.ofSeconds(5))).get(5, TimeUnit.SECONDS)
        .asBoolean());
    // without it the client's own read timeout applies
    server.enqueue(new MockResponse().setHeadersDelay(400, TimeUnit.MILLISECONDS)
        .setBody("{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":7}"));
    assertInstanceOf(java.io.IOException.class, failure(t.call("m", Map.of(), Map.of(),
        RequestOptions.none())));
  }

  @Test
  void aRetryAfterHeaderIsCarriedInSecondsAndAnythingElseIsIgnored() {
    server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "2").setBody("busy"));
    JsonRpcNetworkException seconds = assertInstanceOf(JsonRpcNetworkException.class, failure(call()));
    assertEquals(503, seconds.getStatus());
    assertEquals(2L, seconds.getRetryAfterSeconds());

    server.enqueue(new MockResponse().setResponseCode(429)
        .setHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT").setBody("slow down"));
    assertNull(assertInstanceOf(JsonRpcNetworkException.class, failure(call())).getRetryAfterSeconds());

    server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "-5").setBody("busy"));
    assertNull(assertInstanceOf(JsonRpcNetworkException.class, failure(call())).getRetryAfterSeconds());

    server.enqueue(new MockResponse().setResponseCode(503).setBody("busy"));
    assertNull(assertInstanceOf(JsonRpcNetworkException.class, failure(call())).getRetryAfterSeconds());
  }

  @Test
  void aCallTimeoutAboveTheLimitIsRejectedWithTheLimitNamed() {
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
        () -> RequestOptions.none().withCallTimeout(Duration.ofSeconds(61)));
    assertTrue(refused.getMessage().contains("60 s"), refused.getMessage());
    assertTrue(refused.getMessage().contains("MAX_CALL_TIMEOUT"), refused.getMessage());
    assertThrows(IllegalArgumentException.class,
        () -> RequestOptions.none().withCallTimeout(Duration.ofDays(2)));
    assertEquals(Duration.ofSeconds(60),
        RequestOptions.none().withCallTimeout(RequestOptions.MAX_CALL_TIMEOUT).getCallTimeout());
  }

  @Test
  void everyCallIsBoundedByTheLimitWhateverTheBudgetOrWaitLeft() throws Exception {
    java.util.List<Integer> readTimeouts = new java.util.concurrent.CopyOnWriteArrayList<>();
    // a caller client that disables its timeouts: only the SDK's own limit can bound the call
    OkHttpClient spying = new OkHttpClient.Builder().readTimeout(Duration.ZERO)
        .addInterceptor(chain -> {
          readTimeouts.add(chain.readTimeoutMillis());
          return chain.proceed(chain.request());
        }).build();
    JsonRpcHttpTransport tested = TestTransports.withIds(server.url("/").toString(), spying, 1024,
        () -> IntNode.valueOf(7));
    String ok = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":1}";

    // the budget left is 10 minutes
    server.enqueue(new MockResponse().setBody(ok));
    tested.call("m", Map.of(), Map.of(), RequestOptions.none()
        .withOperation(OperationContext.withBudget(Duration.ofMinutes(10)))).get(5, TimeUnit.SECONDS);
    // an unbounded operation
    server.enqueue(new MockResponse().setBody(ok));
    tested.call("m", Map.of(), Map.of(), RequestOptions.none()
        .withOperation(OperationContext.unbounded())).get(5, TimeUnit.SECONDS);
    // a budget of a month (internal contexts are not limited): clipped, never "no timeout"
    server.enqueue(new MockResponse().setBody(ok));
    tested.call("m", Map.of(), Map.of(), RequestOptions.none()
        .withOperation(OperationContext.withBudget(Duration.ofDays(30)))).get(5, TimeUnit.SECONDS);
    // the largest wait the wire carries
    server.enqueue(new MockResponse().setBody(ok));
    tested.call("m", Map.of(), Map.of(), RequestOptions.none()
        .withWaitMillis(org.unicitylabs.sdk.api.WaitOptions.MAX_WAIT_MILLIS)).get(5, TimeUnit.SECONDS);

    assertEquals(java.util.List.of(60_000, 60_000, 60_000, 60_000), readTimeouts);
  }
}
