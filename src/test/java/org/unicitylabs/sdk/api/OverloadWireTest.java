package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcDataException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcResponseException;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.internal.InclusionProofWaiter;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.MintTransaction;

/**
 * The server-wide busy answer of aggregator-go, over a real transport: HTTP 200 with
 * {@code -32006} and a null id, sent before the request is parsed.
 */
class OverloadWireTest {

  private static final String BUSY = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32006,"
      + "\"message\":\"Concurrency limit exceeded\"},\"id\":null}";
  private static final String PENDING =
      "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32021,\"message\":\"pending\"},\"id\":1}";

  private MockWebServer server;
  private OkHttpClient http;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.start();
    http = new OkHttpClient();
  }

  @AfterEach
  void stop() throws Exception {
    server.shutdown();
    http.dispatcher().executorService().shutdown();
  }

  private StateId stateId() {
    return StateId.fromTransaction(MintTransaction.builder(NetworkId.LOCAL,
        SignaturePredicate.fromSigningService(SigningService.generate())).build());
  }

  private static JsonNode params(RecordedRequest request) throws Exception {
    return WireTestSupport.MAPPER.readTree(request.getBody().readUtf8()).get("params");
  }

  @Test
  void theNullIdBusyAnswerIsAnOverloadAndTheWaitContinuesWithPlainReads() throws Exception {
    String hex = WireTestSupport.MAPPER.readTree(java.nio.file.Files.readAllBytes(
        java.nio.file.Paths.get("src/test/resources/shared/uc-consolidation/v1/shards-1/responses.json")))
        .get("master").get("result").get("proofs").get(0).asText();
    server.enqueue(new MockResponse().setBody(BUSY));
    server.enqueue(new MockResponse().setBody(PENDING));
    server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":\"" + hex + "\",\"id\":1}"));

    CompletableFuture<InclusionProof> wait = InclusionProofWaiter.await(
        WireTestSupport.client(server, http, 1), stateId(), proof -> { },
        WaitOptions.defaults().interval(Duration.ofMillis(20)));
    assertNotNull(wait.get(10, java.util.concurrent.TimeUnit.SECONDS));

    assertEquals(3, server.getRequestCount());
    JsonNode first = params(server.takeRequest());
    assertTrue(first.has("waitMs") || first.toString().contains("wait"), first.toString());
    for (int i = 0; i < 2; i++) {
      assertFalse(params(server.takeRequest()).toString().contains("wait"),
          "after the overload the call polls with plain reads");
    }
  }

  @Test
  void aNullIdErrorThatIsNotRetryableAndAnotherIdAreTerminal() throws Exception {
    server.enqueue(new MockResponse().setBody(
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"db down\"},\"id\":null}"));
    Throwable terminal = WireTestSupport.failure(InclusionProofWaiter.await(
        WireTestSupport.client(server, http, 1), stateId(), proof -> { },
        WaitOptions.defaults().interval(Duration.ofMillis(20))));
    JsonRpcDataException data = assertInstanceOf(JsonRpcDataException.class, terminal);
    assertEquals(-32603, data.getCode());
    assertEquals(1, server.getRequestCount());

    // -32006 that answers another request: a response failure, never an overload
    server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32006,"
        + "\"message\":\"Concurrency limit exceeded\"},\"id\":99}"));
    Throwable other = WireTestSupport.failure(InclusionProofWaiter.await(
        WireTestSupport.client(server, http, 1), stateId(), proof -> { },
        WaitOptions.defaults().interval(Duration.ofMillis(20))));
    assertInstanceOf(JsonRpcResponseException.class, other);
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void submitAndWaitKeepsPollingThroughTheBusyAnswerAndNeverResubmits() throws Exception {
    AtomicInteger submissions = new AtomicInteger();
    AtomicInteger reads = new AtomicInteger();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        String body = request.getBody().readUtf8();
        if (body.contains("\"certification_request\"")) {
          submissions.incrementAndGet();
          return new MockResponse().setBody(
              "{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"SUCCESS\"},\"id\":1}");
        }
        reads.incrementAndGet();
        return new MockResponse().setBody(BUSY);
      }
    });
    TestAggregatorClient local = TestAggregatorClient.create();
    MintTransaction transaction = MintTransaction.builder(NetworkId.LOCAL,
        SignaturePredicate.fromSigningService(SigningService.generate())).build();
    CompletableFuture<InclusionProof> result = new StateTransitionClient(
        WireTestSupport.client(server, http, 1)).submitAndWait(transaction,
        CertificationData.fromMintTransaction(transaction), 3, local.getTrustBase(),
        PredicateVerifierService.create(),
        WaitOptions.defaults().interval(Duration.ofMillis(30)).timeout(Duration.ofSeconds(2)));

    Throwable failure = WireTestSupport.failure(result);
    assertInstanceOf(TimeoutException.class, failure);
    assertEquals(1, submissions.get(), "an overload is no reason to submit again");
    assertTrue(reads.get() >= 5, "the wait kept reading past the grace of two intervals: "
        + reads.get());
  }

  private JsonRpcAggregatorClient smallBodies() {
    return new JsonRpcAggregatorClient(org.unicitylabs.sdk.api.jsonrpc.TestTransports.withIds(
        server.url("/").toString(), http, 65536, () -> com.fasterxml.jackson.databind.node.IntNode.valueOf(1)),
        null);
  }

  /** Exception B: 502 on the held read, 504 (oversized body) on a plain read, then the proof. */
  @Test
  void intermediaryErrorsOnTheHeldAndOnPlainReadsAreRiddenOutWithPlainReads() throws Exception {
    String hex = WireTestSupport.MAPPER.readTree(java.nio.file.Files.readAllBytes(
        java.nio.file.Paths.get("src/test/resources/shared/uc-consolidation/v1/shards-1/responses.json")))
        .get("master").get("result").get("proofs").get(0).asText();
    server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"));
    server.enqueue(new MockResponse().setResponseCode(504).setBody("x".repeat(200_000)));
    server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":\"" + hex + "\",\"id\":1}"));

    long started = System.nanoTime();
    CompletableFuture<InclusionProof> wait = InclusionProofWaiter.await(smallBodies(), stateId(),
        proof -> { }, WaitOptions.defaults().interval(Duration.ofMillis(50)));
    assertNotNull(wait.get(10, java.util.concurrent.TimeUnit.SECONDS));

    assertEquals(3, server.getRequestCount());
    assertTrue(params(server.takeRequest()).toString().contains("wait"), "the first read is held");
    for (int i = 0; i < 2; i++) {
      assertFalse(params(server.takeRequest()).toString().contains("wait"),
          "no wait after an intermediary error");
    }
    assertTrue(System.nanoTime() - started >= Duration.ofMillis(100).toNanos(),
        "the retries are paced by the interval");
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(
        () -> http.dispatcher().runningCallsCount() == 0);
  }

  @Test
  void submitAndWaitSubmitsOnceAndEndsAtTheOriginalDeadlineThroughIntermediaryErrors() throws Exception {
    AtomicInteger submissions = new AtomicInteger();
    AtomicInteger reads = new AtomicInteger();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        if (request.getBody().readUtf8().contains("\"certification_request\"")) {
          submissions.incrementAndGet();
          return new MockResponse().setBody(
              "{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"SUCCESS\"},\"id\":1}");
        }
        return new MockResponse().setResponseCode(reads.incrementAndGet() % 2 == 0 ? 502 : 504)
            .setBody("<html>gateway</html>");
      }
    });
    MintTransaction transaction = MintTransaction.builder(NetworkId.LOCAL,
        SignaturePredicate.fromSigningService(SigningService.generate())).build();
    long started = System.nanoTime();
    Throwable failure = WireTestSupport.failure(new StateTransitionClient(smallBodies()).submitAndWait(
        transaction, CertificationData.fromMintTransaction(transaction), 3,
        TestAggregatorClient.create().getTrustBase(), PredicateVerifierService.create(),
        WaitOptions.defaults().interval(Duration.ofMillis(30)).timeout(Duration.ofSeconds(1))));

    assertInstanceOf(TimeoutException.class, failure);
    assertEquals(1, submissions.get(), "an intermediary error is no reason to submit again");
    assertTrue(reads.get() >= 5, "the wait kept reading: " + reads.get());
    assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos(),
        "no budget extension beyond the original deadline");
  }
}
