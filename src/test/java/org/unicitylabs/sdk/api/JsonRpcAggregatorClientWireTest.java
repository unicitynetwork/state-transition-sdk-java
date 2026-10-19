package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcResponseException;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.internal.OperationContext;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.util.HexConverter;

/** The client over HTTP: request shapes, answer decoding, typed failures and real-call cancellation. */
class JsonRpcAggregatorClientWireTest {

  private MockWebServer server;
  private OkHttpClient http;
  private JsonRpcAggregatorClient client;
  private StateId stateId;
  private CertificationData data;
  private String certifiedHex;
  private String pendingHex;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.start();
    http = new OkHttpClient();
    client = WireTestSupport.client(server, http, 1);

    TestAggregatorClient aggregator = TestAggregatorClient.create();
    MintTransaction transaction = MintTransaction.builder(NetworkId.LOCAL,
        SignaturePredicate.fromSigningService(SigningService.generate()))
        .expiresAt(System.currentTimeMillis() / 1000 + 600).build();
    stateId = StateId.fromTransaction(transaction);
    data = CertificationData.fromMintTransaction(transaction);
    pendingHex = HexConverter.encode(aggregator.getInclusionProof(stateId).get().toCbor());
    aggregator.submitCertificationRequest(data).get();
    certifiedHex = HexConverter.encode(aggregator.getInclusionProof(stateId).get().toCbor());
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

  private static String result(String json) {
    return "{\"jsonrpc\":\"2.0\",\"result\":" + json + ",\"id\":1}";
  }

  private static String error(int code, String data) {
    return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":\"m\""
        + (data == null ? "" : ",\"data\":" + data) + "},\"id\":1}";
  }

  private JsonNode sent() throws Exception {
    return WireTestSupport.MAPPER.readTree(server.takeRequest().getBody().readUtf8());
  }

  // ---- single lookup --------------------------------------------------------------------------

  @Test
  void aPlainLookupSendsOnlyTheStateIdAndReadsTheLegacyString() throws Exception {
    server.enqueue(new MockResponse().setBody(result("\"" + certifiedHex + "\"")));
    InclusionProofResponse response = WireTestSupport.get(client.getInclusionProof(stateId));
    assertNotNull(response.getInclusionProof());
    assertNull(response.getStatusHint());
    assertNull(response.getWaitedMillis());
    JsonNode body = sent();
    assertEquals("get_inclusion_proof.v2", body.get("method").asText());
    assertEquals(List.of("stateId"), fieldNames(body.get("params")));
  }

  @Test
  void theOptionsAreSentAndTheObjectResultCarriesHintAndEcho() throws Exception {
    server.enqueue(new MockResponse().setBody(result(
        "{\"proof\":\"" + pendingHex + "\",\"status\":\"pending\",\"waitedMs\":1500}")));
    InclusionProofResponse response = WireTestSupport.get(client.getInclusionProof(stateId,
        RequestOptions.none().withStatusHint(true).withWaitMillis(5000)));
    assertNull(response.getInclusionProof(), "not certified yet");
    assertEquals(ProofStatusHint.PENDING, response.getStatusHint());
    assertEquals(1500L, response.getWaitedMillis());
    JsonNode params = sent().get("params");
    assertTrue(params.get("includeStatus").asBoolean());
    assertEquals(5000, params.get("waitMs").asInt());
  }

  @Test
  void anUnknownStatusIsTreatedLikeNoStatusAndAMalformedEchoIsAProtocolError() throws Exception {
    server.enqueue(new MockResponse().setBody(result(
        "{\"proof\":\"" + pendingHex + "\",\"status\":\"somethingnew\"}")));
    assertNull(WireTestSupport.get(client.getInclusionProof(stateId,
        RequestOptions.none().withStatusHint(true))).getStatusHint());

    // an echo to a request that asked for no wait, and one that is not a wire integer
    server.enqueue(new MockResponse().setBody(result(
        "{\"proof\":\"" + pendingHex + "\",\"waitedMs\":5}")));
    assertInstanceOf(JsonRpcResponseException.class, WireTestSupport.failure(client.getInclusionProof(stateId)));
    server.enqueue(new MockResponse().setBody(result(
        "{\"proof\":\"" + pendingHex + "\",\"waitedMs\":-1}")));
    assertInstanceOf(JsonRpcResponseException.class, WireTestSupport.failure(client.getInclusionProof(stateId,
        RequestOptions.none().withWaitMillis(5))));
  }

  @Test
  void materialThatIsNotAProofIsAMalformedResponseNeverATransportOrServiceFailure() {
    String[] results = {"\"zz\"", "\"00\"", "42", "{\"proof\":7}", "{}", "null", "[]"};
    for (String result : results) {
      server.enqueue(new MockResponse().setBody(result(result)));
      assertInstanceOf(JsonRpcResponseException.class, WireTestSupport.failure(client.getInclusionProof(stateId)),
          result);
    }
  }

  @Test
  void aCallCarriesTheCallTimeoutAndTheOperation() throws Exception {
    // a call that outlives its own timeout is an IOException the wait treats as inconclusive
    server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody(result("\"" + certifiedHex + "\"")));
    Throwable e = WireTestSupport.failure(client.getInclusionProof(stateId,
        RequestOptions.none().withCallTimeout(Duration.ofMillis(200))));
    assertInstanceOf(java.io.InterruptedIOException.class, e);
  }

  // ---- authentication --------------------------------------------------------------------------

  @Test
  void theApiKeyGoesWithSubmissionsAndBatchesOnlyAndNeverOverPlaintextByDefault() throws Exception {
    JsonRpcAggregatorClient keyed = new JsonRpcAggregatorClient(
        org.unicitylabs.sdk.api.jsonrpc.TestTransports.withIds(server.url("/").toString(), http, 1 << 20,
            () -> IntNode.valueOf(1)), "secret");
    server.enqueue(new MockResponse().setBody(result("{\"status\":\"SUCCESS\"}")));
    WireTestSupport.get(keyed.submitCertificationRequest(data));
    RecordedRequest submit = server.takeRequest();
    assertEquals("Bearer secret", submit.getHeader("Authorization"));
    assertEquals(HexConverter.encode(stateId.getData()), submit.getHeader("X-State-ID"));

    server.enqueue(new MockResponse().setBody(result("\"" + certifiedHex + "\"")));
    WireTestSupport.get(keyed.getInclusionProof(stateId));
    assertNull(server.takeRequest().getHeader("Authorization"));

    server.enqueue(new MockResponse().setBody(result("{\"proofs\":[\"" + certifiedHex + "\"]}")));
    WireTestSupport.get(keyed.getInclusionProofs(List.of(stateId), BatchRequestOptions.latest()));
    assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"));
  }

  // ---- batch -----------------------------------------------------------------------------------

  @Test
  void aBatchIsReadInRequestOrderWithTheViewAndTheEcho() throws Exception {
    server.enqueue(new MockResponse().setBody(result("{\"proofs\":[\"" + certifiedHex + "\",\"" + pendingHex
        + "\"],\"partitionView\":\"707632\",\"waitedMs\":42}")));
    StateId other = WireTestSupport.stateId("ac".repeat(32));
    InclusionProofBatch batch = WireTestSupport.get(client.getInclusionProofs(List.of(stateId, other),
        BatchRequestOptions.latest().withWaitMillis(1000)));
    assertEquals(2, batch.getProofs().size());
    assertNotNull(batch.getProofs().get(0).getInclusionProof());
    assertNull(batch.getProofs().get(1).getInclusionProof());
    assertArrayEquals(new byte[] {0x70, 0x76, 0x32}, batch.getPartitionViewBytes());
    assertEquals(42L, batch.getWaitedMillis());
    assertEquals(1, batch.getRequests());
    JsonNode params = sent().get("params");
    assertEquals("latest", params.get("stateRoot").asText());
    assertEquals(1000, params.get("waitMs").asInt());
    assertEquals(HexConverter.encode(stateId.getData()), params.get("stateIds").get(0).asText());
  }

  @Test
  void anExactRootRequestCarriesTheRootAndNeverAWait() throws Exception {
    server.enqueue(new MockResponse().setBody(result("{\"proofs\":[\"" + certifiedHex + "\"]}")));
    org.unicitylabs.sdk.crypto.hash.DataHash root = new org.unicitylabs.sdk.crypto.hash.DataHash(
        org.unicitylabs.sdk.crypto.hash.HashAlgorithm.SHA256, new byte[32]);
    InclusionProofBatch batch = WireTestSupport.get(client.getInclusionProofs(List.of(stateId),
        BatchRequestOptions.exact(root)));
    assertNull(batch.getPartitionViewBytes());
    JsonNode params = sent().get("params");
    assertEquals(HexConverter.encode(new byte[32]), params.get("stateRoot").asText());
    assertFalse(params.has("waitMs"));
  }

  @Test
  void batchInputIsValidatedLocallyBeforeAnythingIsSent() {
    WireTestSupport.assertRejected(() -> client.getInclusionProofs(List.of(), BatchRequestOptions.latest()));
    WireTestSupport.assertRejected(() -> client.getInclusionProofs(List.of(stateId, stateId), BatchRequestOptions.latest()));
    List<StateId> many = new ArrayList<>();
    for (int i = 0; i < 257; i++) {
      many.add(WireTestSupport.stateId(String.format("%064x", i)));
    }
    WireTestSupport.assertRejected(() -> client.getInclusionProofs(many, BatchRequestOptions.latest()));
    assertEquals(0, server.getRequestCount());
    assertEquals(256, BatchAggregatorClient.BATCH_MAX_STATE_IDS);
  }

  @Test
  void batchFailuresAreTypedAndOnlyAPendingStateIsWorthRetrying() throws Exception {
    server.enqueue(new MockResponse().setBody(error(-32601, null)));
    assertEquals(BatchProofException.Kind.UNSUPPORTED, batchKind());
    server.enqueue(new MockResponse().setBody(error(-32006, "{\"detail\":\"deadline\"}")));
    BatchProofException busy = batch();
    assertEquals(BatchProofException.Kind.UNAVAILABLE, busy.getKind());
    assertTrue(busy.isRetryable());
    assertEquals("busy:deadline", busy.getDetail());
    server.enqueue(new MockResponse().setBody(error(-32008,
        "{\"detail\":\"state_unknown\",\"retryable\":true,\"indexes\":{\"unknown\":[0],\"bogus\":[1]}}")));
    BatchProofException unknown = batch();
    assertFalse(unknown.isRetryable(), "whatever else the data says");
    assertEquals(List.of(0), unknown.getIndexes());
    server.enqueue(new MockResponse().setBody(error(-32008, "{\"detail\":\"unsupported_configuration\"}")));
    assertEquals(BatchProofException.Kind.UNSUPPORTED, batchKind());
    server.enqueue(new MockResponse().setBody(error(-32603, "{\"retryable\":true}")));
    BatchProofException internal = batch();
    assertEquals(BatchProofException.Kind.SERVER, internal.getKind());
    assertTrue(internal.isRetryable());
    server.enqueue(new MockResponse().setBody(error(-32007, "{\"detail\":\"state_count\"}")));
    assertEquals(BatchProofException.Kind.LIMIT_EXCEEDED, batchKind());
  }

  @Test
  void aBadIndexListIsIgnoredNeverTrustedForControlFlow() throws Exception {
    server.enqueue(new MockResponse().setBody(error(-32008,
        "{\"detail\":\"state_pending\",\"indexes\":{\"pending\":[0,-1,\"x\"]}}")));
    assertNull(batch().getIndexes());
  }

  @Test
  void httpAndTransportFailuresOfABatchAreTransportErrors() {
    server.enqueue(new MockResponse().setResponseCode(401).setBody("denied"));
    BatchProofException denied = batch();
    assertEquals(BatchProofException.Kind.TRANSPORT, denied.getKind());
    assertFalse(denied.isRetryable(), "the credential is missing or wrong");
    server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>"));
    assertTrue(batch().isRetryable());
    // OkHttp repeats a request once after a dropped connection
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    BatchProofException io = batch();
    assertEquals(BatchProofException.Kind.TRANSPORT, io.getKind());
    assertInstanceOf(java.io.IOException.class, io.getCause());
    // a body that is not an envelope of this request is an invalid response
    server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"result\":{},\"id\":99}"));
    assertEquals(BatchProofException.Kind.INVALID_RESPONSE, batchKind());
  }

  @Test
  void aBatchDeadlineAndCancellationKeepTheirPlatformIdentity() throws Exception {
    // the budget is spent in fake time while the request is held
    java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
    OperationContext budget = OperationContext.withBudget(Duration.ofSeconds(1), nanos::get);
    server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("{}"));
    CompletableFuture<InclusionProofBatch> spent = client.getInclusionProofs(List.of(stateId),
        BatchRequestOptions.latest().withOperation(budget));
    server.takeRequest(5, TimeUnit.SECONDS);
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
    assertInstanceOf(java.util.concurrent.TimeoutException.class, WireTestSupport.failure(spent));

    server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("{}"));
    OperationContext operation = OperationContext.unbounded();
    CompletableFuture<InclusionProofBatch> future = client.getInclusionProofs(List.of(stateId),
        BatchRequestOptions.latest().withOperation(operation));
    Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> http.dispatcher().runningCallsCount() == 1);
    operation.cancel();
    assertInstanceOf(java.util.concurrent.CancellationException.class, WireTestSupport.failure(future));
  }

  @Test
  void cancellingABatchFutureCancelsTheHttpCallInFlight() throws Exception {
    server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("{}"));
    CompletableFuture<InclusionProofBatch> future =
        client.getInclusionProofs(List.of(stateId), BatchRequestOptions.latest());
    Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> http.dispatcher().runningCallsCount() == 1);
    future.cancel(true);
    Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> http.dispatcher().runningCallsCount() == 0);
  }

  private BatchProofException batch() {
    Throwable e = WireTestSupport.failure(client.getInclusionProofs(List.of(stateId), BatchRequestOptions.latest()));
    return assertInstanceOf(BatchProofException.class, e);
  }

  private BatchProofException.Kind batchKind() {
    return batch().getKind();
  }

  // ---- non-inclusion ---------------------------------------------------------------------------

  private NonInclusionProofException nonInclusion() {
    return assertInstanceOf(NonInclusionProofException.class,
        WireTestSupport.failure(client.getNonInclusionProof(stateId, RequestOptions.none())));
  }

  @Test
  void anAbsenceProofIsBoundToTheQueriedStateAndKeepsItsBytes() throws Exception {
    server.enqueue(new MockResponse().setBody(result("\"0102ff\"")));
    NonInclusionProofResponse response = WireTestSupport.get(client.getNonInclusionProof(stateId, RequestOptions.none()));
    assertEquals(NonInclusionProofResponse.Kind.ABSENCE_PROOF, response.getKind());
    assertEquals(stateId.toString(), response.getProof().getStateId().toString());
    assertArrayEquals(new byte[] {1, 2, (byte) 0xff}, response.getProof().getEncoded());
    JsonNode body = sent();
    assertEquals("get_non_inclusion_proof.v1", body.get("method").asText());
    assertEquals(List.of("stateId"), fieldNames(body.get("params")));
  }

  @Test
  void theIncludedClaimIsAnUnauthenticatedHintAlsoBehindAnHttpErrorStatus() throws Exception {
    server.enqueue(new MockResponse().setBody(error(-32022, null)));
    assertEquals(NonInclusionProofResponse.Kind.STATE_INCLUDED_HINT,
        WireTestSupport.get(client.getNonInclusionProof(stateId, RequestOptions.none())).getKind());
    server.enqueue(new MockResponse().setResponseCode(409).setBody(error(-32022, null)));
    assertEquals(NonInclusionProofResponse.Kind.STATE_INCLUDED_HINT,
        WireTestSupport.get(client.getNonInclusionProof(stateId, RequestOptions.none())).getKind());
  }

  @Test
  void nonInclusionFailuresAreTyped() {
    server.enqueue(new MockResponse().setBody(error(-32020, null)));
    NonInclusionProofException unavailable = nonInclusion();
    assertEquals(NonInclusionProofException.Kind.UNAVAILABLE, unavailable.getKind());
    assertTrue(unavailable.isRetryable());
    server.enqueue(new MockResponse().setBody(error(-32023, null)));
    assertEquals(NonInclusionProofException.Kind.BUSY, nonInclusion().getKind());
    server.enqueue(new MockResponse().setBody(error(-32602, null)));
    assertEquals(NonInclusionProofException.Kind.REJECTED, nonInclusion().getKind());
    server.enqueue(new MockResponse().setBody(error(-32603, null)));
    assertEquals(NonInclusionProofException.Kind.SERVER, nonInclusion().getKind());
    server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>"));
    assertEquals(NonInclusionProofException.Kind.TRANSPORT, nonInclusion().getKind());
    // an error that carries another id answers nothing of ours
    server.enqueue(new MockResponse().setBody(
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32022,\"message\":\"m\"},\"id\":null}"));
    assertEquals(NonInclusionProofException.Kind.INVALID_RESPONSE, nonInclusion().getKind());
    // not hex, and a result that would never be decoded because it is over the bound
    server.enqueue(new MockResponse().setBody(result("\"zz\"")));
    assertEquals(NonInclusionProofException.Kind.INVALID_RESPONSE, nonInclusion().getKind());
    server.enqueue(new MockResponse().setBody(result("\"" + "00".repeat(128 * 1024 + 8) + "\"")));
    assertEquals(NonInclusionProofException.Kind.INVALID_RESPONSE, nonInclusion().getKind());
    server.enqueue(new MockResponse().setBody(result("7")));
    assertEquals(NonInclusionProofException.Kind.INVALID_RESPONSE, nonInclusion().getKind());
  }

  @Test
  void nonInclusionTakesNoWaitAndCancellationAndDeadlineAreTyped() throws Exception {
    assertThrows(IllegalArgumentException.class,
        () -> client.getNonInclusionProof(stateId, RequestOptions.none().withWaitMillis(0)));
    java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
    OperationContext budget = OperationContext.withBudget(Duration.ofSeconds(1), nanos::get);
    server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("{}"));
    CompletableFuture<NonInclusionProofResponse> spent =
        client.getNonInclusionProof(stateId, RequestOptions.none().withOperation(budget));
    server.takeRequest(5, TimeUnit.SECONDS);
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
    NonInclusionProofException timeout = assertInstanceOf(NonInclusionProofException.class,
        WireTestSupport.failure(spent));
    assertEquals(NonInclusionProofException.Kind.TIMEOUT, timeout.getKind());
    OperationContext cancelled = OperationContext.unbounded();
    cancelled.cancel();
    assertEquals(NonInclusionProofException.Kind.CANCELLED, assertInstanceOf(NonInclusionProofException.class,
        WireTestSupport.failure(client.getNonInclusionProof(stateId, RequestOptions.none().withOperation(cancelled)))).getKind());
  }

  // ---- submission ------------------------------------------------------------------------------

  @Test
  void aPlainSubmissionKeepsItsErrorSemanticsForADuplicate() {
    server.enqueue(new MockResponse().setBody(error(-32603,
        "\"E11000 duplicate key error collection: x index: stateId_1 dup key\"")));
    assertInstanceOf(org.unicitylabs.sdk.api.jsonrpc.JsonRpcDataException.class,
        WireTestSupport.failure(client.submitCertificationRequest(data)));
  }

  @Test
  void aSubmissionResultWithoutAStatusIsAnInvalidResponseNeverANullOrAStrandedFuture() {
    String[] results = {"null", "{}", "{\"status\":5}", "\"SUCCESS\"", "[]"};
    for (String result : results) {
      server.enqueue(new MockResponse().setBody(result(result)));
      assertInstanceOf(JsonRpcResponseException.class, WireTestSupport.failure(client.submitCertificationRequest(data)),
          result);
    }
    // and through the facade the exposed future ends too
    server.enqueue(new MockResponse().setBody(result("null")));
    assertInstanceOf(JsonRpcResponseException.class, WireTestSupport.failure(
        new org.unicitylabs.sdk.StateTransitionClient(client).resubmitCertificationRequest(data, RequestOptions.none())));
  }

  @Test
  void anUnmatchedDuplicateReportIsNotAnAcknowledgement() {
    server.enqueue(new MockResponse().setBody(
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"m\",\"data\":"
            + "\"E11000 duplicate key index: stateId_1\"},\"id\":null}"));
    assertInstanceOf(org.unicitylabs.sdk.api.jsonrpc.JsonRpcDataException.class, WireTestSupport.failure(
        new org.unicitylabs.sdk.StateTransitionClient(client).resubmitCertificationRequest(data, RequestOptions.none())));
  }

  @Test
  void aSubmissionCannotBeWaitedOn() {
    WireTestSupport.assertRejected(() -> client.submitCertificationRequest(data, RequestOptions.none().withWaitMillis(1)));
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
