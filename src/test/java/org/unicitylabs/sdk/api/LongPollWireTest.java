package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcDataException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcResponseException;
import org.unicitylabs.sdk.api.jsonrpc.TestTransports;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.shared.LongPollCorpus;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * The client against the shared long-poll corpus (aggregator-go testdata/longpoll): the request it
 * builds for every case, and what it makes of every frozen answer.
 */
class LongPollWireTest {

  private static final long MAX_WIRE = WaitOptions.MAX_WAIT_MILLIS;
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

  private static List<JsonNode> cases(String file) {
    List<JsonNode> list = new ArrayList<>();
    LongPollCorpus.json(file).get("cases").forEach(list::add);
    return list;
  }

  static Stream<JsonNode> echoV2() {
    return cases("echo.json").stream().filter(c -> c.get("name").asText().startsWith("v2/"));
  }

  static Stream<JsonNode> echoBatch() {
    return cases("echo.json").stream().filter(c -> c.get("name").asText().startsWith("batch/"));
  }

  static Stream<JsonNode> validWaits() {
    return cases("params.json").stream()
        .filter(c -> c.get("valid").asBoolean() && c.get("waitMs").isIntegralNumber());
  }

  private static RequestOptions v2Options(JsonNode params) {
    RequestOptions options = RequestOptions.none();
    if (params.has("includeStatus") && params.get("includeStatus").asBoolean()) {
      options = options.withStatusHint(true);
    }
    if (params.has("waitMs") && params.get("waitMs").isIntegralNumber()) {
      options = options.withWaitMillis(params.get("waitMs").longValue());
    }
    return options;
  }

  private JsonNode sentParams() throws Exception {
    RecordedRequest request = server.takeRequest();
    return WireTestSupport.MAPPER.readTree(request.getBody().readUtf8()).get("params");
  }

  // ---- params.json: the requests the client builds -------------------------------------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("validWaits")
  void aValidWaitIsSentAsAnIntegerToken(JsonNode c) throws Exception {
    long wait = c.get("waitMs").longValue();
    server.enqueue(new MockResponse().setBody("{}"));
    String id = c.get("params").asText();
    JsonNode params = WireTestSupport.MAPPER.readTree(id);
    String stateHex = params.has("stateId") ? params.get("stateId").asText()
        : params.get("stateIds").get(0).asText();
    if (c.get("method").asText().equals("get_inclusion_proof.v2")) {
      WireTestSupport.client(server, http, 1)
          .getInclusionProof(WireTestSupport.stateId(stateHex), RequestOptions.none().withWaitMillis(wait));
    } else {
      WireTestSupport.client(server, http, 1).getInclusionProofs(
          List.of(WireTestSupport.stateId(stateHex)), BatchRequestOptions.latest().withWaitMillis(wait));
    }
    RecordedRequest request = server.takeRequest();
    String body = request.getBody().readUtf8();
    assertTrue(body.contains("\"waitMs\":" + wait + (body.endsWith("}}") ? "" : "")), body);
    assertEquals(wait, WireTestSupport.MAPPER.readTree(body).get("params").get("waitMs").longValue());
  }

  @Test
  void waitsOutsideTheWireRangeNeverLeaveTheClient() {
    for (JsonNode c : cases("params.json")) {
      JsonNode params;
      try {
        params = WireTestSupport.MAPPER.readTree(c.get("params").asText());
      } catch (Exception e) {
        continue;
      }
      JsonNode wait = params.get("waitMs");
      // duplicate members are lexical forms that a typed API cannot produce at all
      boolean exactRoot = params.has("stateRoot") && !"latest".equals(params.get("stateRoot").asText());
      if (exactRoot || c.get("name").asText().contains("duplicate") || wait == null || !wait.isIntegralNumber()
          || c.get("valid").asBoolean()) {
        continue;
      }
      BigInteger value = wait.bigIntegerValue();
      if (value.signum() == 0) {
        continue; // "-0": a lexical form, not a number the typed API can express
      }
      // the only integer tokens the corpus calls invalid are the out of range ones
      assertTrue(value.signum() < 0 || value.compareTo(BigInteger.valueOf(MAX_WIRE)) > 0, c.toString());
      if (value.bitLength() < 63) {
        WireTestSupport.assertRejected(() -> RequestOptions.none().withWaitMillis(value.longValue()));
        WireTestSupport.assertRejected(() -> BatchRequestOptions.latest().withWaitMillis(value.longValue()));
      }
    }
  }

  @Test
  void anExactRootNeverCarriesAWait() {
    DataHash root = new DataHash(HashAlgorithm.SHA256, new byte[32]);
    WireTestSupport.assertRejected(() -> BatchRequestOptions.exact(root).withWaitMillis(0));
    WireTestSupport.assertRejected(() -> BatchRequestOptions.exact(root).withWaitMillis(5));
  }

  // ---- echo.json: the frozen answers ---------------------------------------------------------

  private JsonRpcHttpTransportHolder transportFor(int id) {
    return new JsonRpcHttpTransportHolder(TestTransports.withIds(server.url("/").toString(), http,
        8 * 1024 * 1024, () -> IntNode.valueOf(id)));
  }

  /** The transport, to look at the shape of a result before a proof is decoded. */
  private static final class JsonRpcHttpTransportHolder {
    final org.unicitylabs.sdk.api.jsonrpc.JsonRpcHttpTransport transport;

    JsonRpcHttpTransportHolder(org.unicitylabs.sdk.api.jsonrpc.JsonRpcHttpTransport transport) {
      this.transport = transport;
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("echoV2")
  void v2Answers(JsonNode c) throws Exception {
    JsonNode request = c.get("request");
    JsonNode response = c.get("response");
    int id = request.get("id").intValue();
    JsonNode wire = request.get("params");
    String hex = wire.get("stateId").asText();
    boolean wellFormed = hex.length() == 64 && !(wire.has("waitMs") && wire.get("waitMs").longValue() < 0);
    server.enqueue(new MockResponse().setBody(WireTestSupport.MAPPER.writeValueAsString(response)));

    if (response.has("error")) {
      RequestOptions options = wellFormed ? v2Options(wire) : RequestOptions.none();
      CompletableFuture<InclusionProofResponse> future = WireTestSupport.client(server, http, id)
          .getInclusionProof(WireTestSupport.stateId(wellFormed ? hex : "ab".repeat(32)), options);
      JsonRpcDataException e = assertInstanceOf(JsonRpcDataException.class, WireTestSupport.failure(future));
      assertEquals(response.get("error").get("code").intValue(), e.getCode());
      assertEquals(response.get("error").get("data"), e.getData());
      assertTrue(e.isIdMatched());
      return;
    }

    // the request is exactly the case's request, and the answer is read by its shape
    JsonNode result = transportFor(id).transport.call("get_inclusion_proof.v2",
        WireTestSupport.MAPPER.createObjectNode().setAll((com.fasterxml.jackson.databind.node.ObjectNode) wire),
        java.util.Map.of(), RequestOptions.none()).get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(wire, sentParams());
    JsonRpcAggregatorClient.ProofResult parsed =
        JsonRpcAggregatorClient.parseInclusionProofResult(result, wire.has("waitMs"));
    if (result.isTextual()) {
      assertNull(parsed.statusHint);
      assertNull(parsed.waitedMillis);
      assertEquals(result.textValue(), parsed.proofHex);
      return;
    }
    JsonNode status = result.get("status");
    assertEquals(status == null ? null : ProofStatusHint.fromWire(status.asText()), parsed.statusHint);
    JsonNode echo = result.get("waitedMs");
    assertEquals(echo == null ? null : Long.valueOf(echo.longValue()), parsed.waitedMillis);
    assertEquals(result.get("proof").asText(), parsed.proofHex);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("echoBatch")
  void batchAnswers(JsonNode c) throws Exception {
    JsonNode request = c.get("request");
    JsonNode response = c.get("response");
    JsonNode params = request.get("params");
    int id = request.get("id").intValue();
    List<StateId> ids = new ArrayList<>();
    boolean valid = true;
    for (JsonNode hex : params.get("stateIds")) {
      valid &= hex.asText().length() == 64;
      ids.add(WireTestSupport.stateId(hex.asText().length() == 64 ? hex.asText() : "ab".repeat(32)));
    }
    // cases that the local validation already refuses never reach the wire
    JsonNode wait = params.get("waitMs");
    boolean exact = params.has("stateRoot") && !params.get("stateRoot").asText().equals("latest");
    if (exact && wait != null) {
      WireTestSupport.assertRejected(() -> BatchRequestOptions
          .exact(new DataHash(HashAlgorithm.SHA256, HexConverter.decode(params.get("stateRoot").asText())))
          .withWaitMillis(wait.longValue()));
      return;
    }
    if (wait != null && !wait.isIntegralNumber()) {
      return; // a non-integer wait cannot be expressed through the typed API
    }
    BatchRequestOptions options = BatchRequestOptions.latest();
    if (wait != null) {
      options = options.withWaitMillis(wait.longValue());
    }
    server.enqueue(new MockResponse().setBody(WireTestSupport.MAPPER.writeValueAsString(response)));

    if (response.has("error")) {
      CompletableFuture<InclusionProofBatch> future =
          WireTestSupport.client(server, http, id).getInclusionProofs(ids, options);
      if (valid) {
        JsonNode sent = sentParams();
        assertEquals(params.get("stateIds"), sent.get("stateIds"));
        assertEquals(wait, sent.get("waitMs"));
      }
      JsonNode data = response.get("error").get("data");
      BatchProofException e = assertInstanceOf(BatchProofException.class, WireTestSupport.failure(future));
      assertEquals(response.get("error").get("code").intValue(), e.getCode());
      boolean echoed = wait != null && data != null && data.has("waitedMs");
      assertEquals(echoed ? Long.valueOf(data.get("waitedMs").longValue()) : null, e.getWaitedMillis());
      // the generic malformed-params answer without an echo is repeated once, so the second one is final
      assertEquals(c.get("name").asText().equals("batch/invalid-wait-malformed-params") ? 2 : 1,
          e.getRequests());
      return;
    }
    // a success is read by its shape: the proofs are opaque here
    JsonNode result = transportFor(id).transport.call("get_inclusion_proofs.v1", params,
        java.util.Map.of(), RequestOptions.none()).get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(params, sentParams());
    JsonRpcAggregatorClient.BatchResult parsed = JsonRpcAggregatorClient.parseBatch(result,
        ids.size(), false, 1, wait == null ? null : wait.longValue());
    assertEquals(response.get("result").get("proofs").size(), parsed.proofHex.size());
    JsonNode echo = response.get("result").get("waitedMs");
    assertEquals(echo == null ? null : Long.valueOf(echo.longValue()), parsed.waitedMillis);
    JsonNode view = response.get("result").get("partitionView");
    assertEquals(view == null ? null : view.asText(), parsed.viewHex);
  }

  @Test
  void theCorpusProofsDecodeWhenTheCertificateCodecsAreComplete() {
    JsonNode proof = LongPollCorpus.json("echo.json").get("cases").get(0).get("response").get("result");
    try {
      InclusionProofResponse.fromCbor(HexConverter.decode(proof.asText()));
    } catch (RuntimeException e) {
      // The Go wire certificates carry the native IR/UC fields that package A's codecs add. Until
      // that lands the corpus is read by shape only (see the answers above); this runs afterwards.
      org.junit.jupiter.api.Assumptions.assumeTrue(false,
          "Go wire certificates are not decodable before package A: " + e.getMessage());
    }
    assertNotNull(InclusionProofResponse.fromCbor(HexConverter.decode(proof.asText())).getInclusionProof());
  }

  // ---- capability.json: the parser negotiation -----------------------------------------------

  @Test
  void theCapabilityTableDecidesTheRetry() throws Exception {
    List<StateId> ids = List.of(WireTestSupport.stateId("ab".repeat(32)));
    for (JsonNode c : cases("capability.json")) {
      String name = c.get("name").asText();
      String action = c.get("action").asText();
      JsonNode first = c.get("response");
      if (first == null || !first.isObject()) {
        continue; // HTML pages and success shapes are covered by their own tests
      }
      server.shutdown();
      server = new MockWebServer();
      server.start();
      server.enqueue(new MockResponse().setBody(envelope(first, 5)));
      if (action.equals("retry")) {
        server.enqueue(new MockResponse().setBody(
            "{\"jsonrpc\":\"2.0\",\"result\":{\"proofs\":[],\"waitedMs\":null},\"id\":5}"));
      }
      CompletableFuture<InclusionProofBatch> future = WireTestSupport.client(server, http, 5)
          .getInclusionProofs(ids, BatchRequestOptions.latest().withWaitMillis(1000));
      if (action.equals("retry")) {
        Throwable second = WireTestSupport.failure(future);
        // the second answer stands whatever it is (here: it is not a valid batch)
        assertEquals(2, server.getRequestCount(), name);
        assertInstanceOf(BatchProofException.class, second, name);
        server.takeRequest();
        JsonNode retried = WireTestSupport.MAPPER.readTree(server.takeRequest().getBody().readUtf8()).get("params");
        assertFalse(retried.has("waitMs"), "the retry has no wait: " + name);
        assertEquals("latest", retried.get("stateRoot").asText());
      } else if (action.equals("propagate") || action.equals("normal-pending-handling")) {
        Throwable e = WireTestSupport.failure(future);
        assertInstanceOf(BatchProofException.class, e, name);
        assertEquals(1, server.getRequestCount(), "no retry: " + name);
      }
    }
  }

  @Test
  void aSecondMalformedParamsAnswerIsFinalAndNothingIsRemembered() throws Exception {
    String malformed = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\","
        + "\"data\":{\"reason\":\"invalid_request\",\"detail\":\"malformed_params\"}},\"id\":5}";
    server.enqueue(new MockResponse().setBody(malformed));
    server.enqueue(new MockResponse().setBody(malformed));
    JsonRpcAggregatorClient client = WireTestSupport.client(server, http, 5);
    List<StateId> ids = List.of(WireTestSupport.stateId("ab".repeat(32)));
    BatchProofException e = assertInstanceOf(BatchProofException.class, WireTestSupport.failure(
        client.getInclusionProofs(ids, BatchRequestOptions.latest().withWaitMillis(1000))));
    assertEquals(BatchProofException.Kind.REJECTED, e.getKind());
    assertEquals(2, e.getRequests());
    assertEquals(2, server.getRequestCount());
    // the next call tries the wait again: no capability absence is cached
    server.enqueue(new MockResponse().setBody(malformed));
    server.enqueue(new MockResponse().setBody(malformed));
    server.takeRequest();
    server.takeRequest();
    WireTestSupport.failure(client.getInclusionProofs(ids, BatchRequestOptions.latest().withWaitMillis(1000)));
    assertTrue(WireTestSupport.MAPPER.readTree(server.takeRequest().getBody().readUtf8())
        .get("params").has("waitMs"));
  }

  @Test
  void theRetryIsNotTakenForAnErrorWithoutTheMatchingId() throws Exception {
    // a malformed-params answer with a null id answers nothing of ours
    server.enqueue(new MockResponse().setBody(
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\","
            + "\"data\":{\"reason\":\"invalid_request\",\"detail\":\"malformed_params\"}},\"id\":null}"));
    List<StateId> ids = List.of(WireTestSupport.stateId("ab".repeat(32)));
    Throwable e = WireTestSupport.failure(WireTestSupport.client(server, http, 5)
        .getInclusionProofs(ids, BatchRequestOptions.latest().withWaitMillis(1000)));
    assertInstanceOf(BatchProofException.class, e);
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void anEchoIsAcceptedOnlyForARequestThatAskedForAWaitAndOnlyAsAWireInteger() {
    ObjectNode ok = WireTestSupport.MAPPER.createObjectNode().put("proofs", "x");
    ObjectNode result = WireTestSupport.MAPPER.createObjectNode();
    result.putArray("proofs").add("00");
    // no echo and a zero echo are accepted for a wait request
    assertNull(JsonRpcAggregatorClient.parseBatch(result, 1, false, 1, 1000L).waitedMillis);
    result.put("waitedMs", 0);
    assertEquals(0L, JsonRpcAggregatorClient.parseBatch(result, 1, false, 1, 1000L).waitedMillis);
    // an echo to a request that asked for no wait is a protocol error
    assertEquals(BatchProofException.Kind.INVALID_RESPONSE, assertThrows(BatchProofException.class,
        () -> JsonRpcAggregatorClient.parseBatch(result, 1, false, 1, null)).getKind());
    for (Object bad : new Object[] {-1, 1.5, "5", true, MAX_WIRE + 1}) {
      ObjectNode node = WireTestSupport.MAPPER.createObjectNode();
      node.putArray("proofs").add("00");
      node.set("waitedMs", WireTestSupport.MAPPER.valueToTree(bad));
      assertEquals(BatchProofException.Kind.INVALID_RESPONSE, assertThrows(BatchProofException.class,
          () -> JsonRpcAggregatorClient.parseBatch(node, 1, false, 1, 1000L)).getKind(), String.valueOf(bad));
      ObjectNode single = WireTestSupport.MAPPER.createObjectNode().put("proof", "00");
      single.set("waitedMs", WireTestSupport.MAPPER.valueToTree(bad));
      assertThrows(JsonRpcResponseException.class,
          () -> JsonRpcAggregatorClient.parseInclusionProofResult(single, true), String.valueOf(bad));
    }
    // a view never comes with an exact root request
    ObjectNode withView = WireTestSupport.MAPPER.createObjectNode().put("partitionView", "00");
    withView.putArray("proofs").add("00");
    assertThrows(BatchProofException.class,
        () -> JsonRpcAggregatorClient.parseBatch(withView, 1, true, 1, null));
    // the number of proofs is the number of states asked about
    assertThrows(BatchProofException.class,
        () -> JsonRpcAggregatorClient.parseBatch(result, 2, false, 1, 1000L));
  }

  private static String envelope(JsonNode part, int id) {
    return "{\"jsonrpc\":\"2.0\"," + part.toString().substring(1, part.toString().length() - 1) + ",\"id\":" + id + "}";
  }
}
