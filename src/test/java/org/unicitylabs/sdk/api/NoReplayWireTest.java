package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcNetworkException;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.internal.InclusionProofWaiter;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.transaction.MintTransaction;

/**
 * A JSON-RPC POST is never replayed by the transport. OkHttp replays a request on a 503 whose
 * Retry-After is 0 and on a 408; a replayed submission has an uncertain outcome and a replayed read
 * would hide an overload from the waiter. Each case answers the request once with the error and
 * queues a success behind it: exactly one request may reach the server.
 */
class NoReplayWireTest {

  private static final String OK = "{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"SUCCESS\"},\"id\":1}";

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

  private void errorThenSuccess(int status, String retryAfter, String success) {
    MockResponse error = new MockResponse().setResponseCode(status).setBody("<html>busy</html>");
    if (retryAfter != null) {
      error.setHeader("Retry-After", retryAfter);
    }
    server.enqueue(error);
    server.enqueue(new MockResponse().setBody(success));
  }

  private static JsonNode params(RecordedRequest request) throws Exception {
    return WireTestSupport.MAPPER.readTree(request.getBody().readUtf8()).get("params");
  }

  private StateId stateId() {
    return StateId.fromTransaction(MintTransaction.builder(NetworkId.LOCAL,
        SignaturePredicate.fromSigningService(SigningService.generate())).build());
  }

  @ParameterizedTest
  @CsvSource(value = {"503,0", "503,000", "503,0000000000000000000", "408,0", "408,NONE", "503,NONE"})
  void aSubmissionIsSentExactlyOnce(int status, String retryAfter) throws Exception {
    errorThenSuccess(status, "NONE".equals(retryAfter) ? null : retryAfter, OK);
    MintTransaction transaction = MintTransaction.builder(NetworkId.LOCAL,
        SignaturePredicate.fromSigningService(SigningService.generate())).build();
    Throwable failure = WireTestSupport.failure(WireTestSupport.client(server, http, 1)
        .submitCertificationRequest(CertificationData.fromMintTransaction(transaction)));
    JsonRpcNetworkException error = assertInstanceOf(JsonRpcNetworkException.class, failure);
    assertEquals(status, error.getStatus());
    if (!"NONE".equals(retryAfter)) {
      assertEquals(0L, error.getRetryAfterSeconds(), "the typed failure keeps the parsed Retry-After");
    }
    assertEquals(1, server.getRequestCount(), "a submission must never be replayed by the transport");
  }

  @ParameterizedTest
  @CsvSource(value = {"503,0", "503,000", "408,0", "408,NONE"})
  void aOneShotReadIsSentExactlyOnce(int status, String retryAfter) {
    errorThenSuccess(status, "NONE".equals(retryAfter) ? null : retryAfter, "{}");
    Throwable failure = WireTestSupport.failure(WireTestSupport.client(server, http, 1)
        .getInclusionProof(stateId()));
    assertEquals(status, assertInstanceOf(JsonRpcNetworkException.class, failure).getStatus());
    assertEquals(1, server.getRequestCount());
  }

  @ParameterizedTest
  @CsvSource(value = {"503,0", "503,000"})
  void aWaitSeesTheOverloadPacesItAndPollsWithoutTheWait(int status, String retryAfter)
      throws Exception {
    String hex = WireTestSupport.MAPPER.readTree(java.nio.file.Files.readAllBytes(
        java.nio.file.Paths.get("src/test/resources/shared/uc-consolidation/v1/shards-1/responses.json")))
        .get("master").get("result").get("proofs").get(0).asText();
    errorThenSuccess(status, retryAfter, "{\"jsonrpc\":\"2.0\",\"result\":\"" + hex + "\",\"id\":1}");

    long started = System.nanoTime();
    CompletableFuture<InclusionProof> wait = InclusionProofWaiter.await(
        WireTestSupport.client(server, http, 1), stateId(), proof -> { },
        WaitOptions.defaults().interval(Duration.ofMillis(400)));
    assertNotNull(wait.get(10, TimeUnit.SECONDS));

    assertEquals(2, server.getRequestCount(), "the 503 reached the waiter: one request each side");
    assertTrue(params(server.takeRequest()).toString().contains("wait"), "the first read is held");
    assertFalse(params(server.takeRequest()).toString().contains("wait"),
        "after the overload the wait polls without waitMs");
    assertTrue(System.nanoTime() - started >= Duration.ofMillis(350).toNanos(),
        "the retry is paced by the interval, not immediate");
  }

  @ParameterizedTest
  @CsvSource(value = {"408,0", "408,NONE"})
  void aWaitThatMeetsA408EndsWithItAndSendsOneRequest(int status, String retryAfter) {
    errorThenSuccess(status, "NONE".equals(retryAfter) ? null : retryAfter, "{}");
    Throwable failure = WireTestSupport.failure(InclusionProofWaiter.await(
        WireTestSupport.client(server, http, 1), stateId(), proof -> { },
        WaitOptions.defaults().interval(Duration.ofMillis(20))));
    assertEquals(408, assertInstanceOf(JsonRpcNetworkException.class, failure).getStatus());
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void aCallerSuppliedClientThatRetriesOnConnectionFailureIsStillNeverReplayed() {
    OkHttpClient eager = new OkHttpClient.Builder().retryOnConnectionFailure(true).build();
    try {
      errorThenSuccess(408, "0", OK);
      Throwable failure = WireTestSupport.failure(WireTestSupport.client(server, eager, 1)
          .getInclusionProof(stateId()));
      assertEquals(408, assertInstanceOf(JsonRpcNetworkException.class, failure).getStatus());
      assertEquals(1, server.getRequestCount());
    } finally {
      eager.dispatcher().executorService().shutdown();
    }
  }

  @Test
  void aMissingIdIsAResponseFailureAndOnlyAnExplicitNullIsTheBusyException() {
    // data.retryable:true does not rescue a missing id either
    server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32006,"
        + "\"message\":\"busy\",\"data\":{\"retryable\":true}}}"));
    server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32006,"
        + "\"message\":\"busy\"}}"));
    server.enqueue(new MockResponse().setBody(OK));
    for (int i = 0; i < 2; i++) {
      Throwable failure = WireTestSupport.failure(InclusionProofWaiter.await(
          WireTestSupport.client(server, http, 1), stateId(), proof -> { },
          WaitOptions.defaults().interval(Duration.ofMillis(20))));
      assertInstanceOf(org.unicitylabs.sdk.api.jsonrpc.JsonRpcResponseException.class, failure);
      assertEquals(i + 1, server.getRequestCount(), "no second read after a missing id");
    }
  }
}
