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
    assertEquals("m", e.getMessage());
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

    server.enqueue(new MockResponse().setHeadersDelay(10, TimeUnit.SECONDS).setBody("{}"));
    OperationContext budget = OperationContext.withBudget(Duration.ofMillis(300));
    assertInstanceOf(TimeoutException.class, failure(transport.call("m", Map.of(), Map.of(),
        RequestOptions.none().withOperation(budget))));
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
}
