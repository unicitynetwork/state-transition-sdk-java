package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcDataException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcHttpTransport;
import org.unicitylabs.sdk.api.jsonrpc.TestTransports;
import org.unicitylabs.sdk.shared.LongPollCorpus;

/**
 * Replays the recorded no-wait exchanges of the Go handlers (golden/): the request bodies, and the
 * response bodies as raw bytes, the trailing newline included. The client must read every one of
 * them without normalizing anything away, and answers without a wait carry no echo.
 */
class GoldenReplayTest {

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

  static Stream<JsonNode> v2() {
    List<JsonNode> cases = new ArrayList<>();
    LongPollCorpus.json("golden/v2-nowait.json").forEach(cases::add);
    return cases.stream();
  }

  static Stream<JsonNode> batch() {
    List<JsonNode> cases = new ArrayList<>();
    LongPollCorpus.json("golden/batch-nowait.json").forEach(cases::add);
    return cases.stream();
  }

  /** The recorded request; the one that is not a JSON document (trailing data) is only a probe of the parser. */
  private static JsonNode request(JsonNode c) throws Exception {
    try {
      return WireTestSupport.MAPPER.readTree(c.get("request").asText());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return WireTestSupport.MAPPER.readTree(
          "{\"id\":17,\"method\":\"get_inclusion_proofs.v1\",\"params\":{\"stateIds\":[\"ab\"]}}");
    }
  }

  private JsonNode replay(JsonNode c, boolean[] failed, Throwable[] error) throws Exception {
    JsonNode request = request(c);
    // the recorded response, exactly as the handler wrote it
    server.enqueue(new MockResponse().setBody(new Buffer().writeUtf8(c.get("response").asText())));
    JsonRpcHttpTransport transport = TestTransports.withIds(server.url("/").toString(), http,
        8 * 1024 * 1024, () -> IntNode.valueOf(request.get("id").intValue()));
    try {
      JsonNode result = transport.call(request.get("method").asText(), request.get("params"), Map.of(),
          RequestOptions.none()).get(5, TimeUnit.SECONDS);
      return result;
    } catch (java.util.concurrent.ExecutionException e) {
      failed[0] = true;
      error[0] = e.getCause();
      return null;
    } finally {
      // and the request left the client as recorded
      assertEquals(request.get("params"),
          WireTestSupport.MAPPER.readTree(server.takeRequest().getBody().readUtf8()).get("params"));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("v2")
  void v2NoWait(JsonNode c) throws Exception {
    boolean[] failed = new boolean[1];
    Throwable[] error = new Throwable[1];
    JsonNode result = replay(c, failed, error);
    JsonNode recorded = WireTestSupport.MAPPER.readTree(c.get("response").asText());
    if (recorded.has("error")) {
      assertTrue(failed[0]);
      JsonRpcDataException e = assertInstanceOf(JsonRpcDataException.class, error[0]);
      assertEquals(recorded.get("error").get("code").intValue(), e.getCode());
      assertEquals(recorded.get("error").get("data"), e.getData());
      return;
    }
    JsonRpcAggregatorClient.ProofResult parsed =
        JsonRpcAggregatorClient.parseInclusionProofResult(result, false);
    assertNull(parsed.waitedMillis, "no wait asked, no echo");
    JsonNode status = recorded.get("result").isObject() ? recorded.get("result").get("status") : null;
    assertEquals(status == null ? null : ProofStatusHint.fromWire(status.asText()), parsed.statusHint);
    assertEquals(recorded.get("result").isObject() ? recorded.get("result").get("proof").asText()
        : recorded.get("result").asText(), parsed.proofHex);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("batch")
  void batchNoWait(JsonNode c) throws Exception {
    boolean[] failed = new boolean[1];
    Throwable[] error = new Throwable[1];
    JsonNode result = replay(c, failed, error);
    JsonNode recorded = WireTestSupport.MAPPER.readTree(c.get("response").asText());
    JsonNode params = request(c).get("params");
    if (recorded.has("error")) {
      assertTrue(failed[0]);
      JsonRpcDataException e = assertInstanceOf(JsonRpcDataException.class, error[0]);
      assertEquals(recorded.get("error").get("code").intValue(), e.getCode());
      assertEquals(recorded.get("id").isNull(), !e.isIdMatched());
      Throwable classified = JsonRpcAggregatorClient.batchFailure(e, 1, null);
      BatchProofException typed = assertInstanceOf(BatchProofException.class, classified);
      if (!e.isIdMatched()) {
        // an error without the id of the request answers nothing of ours: no classification
        assertEquals(BatchProofException.Kind.INVALID_RESPONSE, typed.getKind());
        return;
      }
      assertEquals(e.getCode(), typed.getCode());
      assertNull(typed.getWaitedMillis());
      JsonNode data = recorded.get("error").get("data");
      if (recorded.get("error").get("code").intValue() == -32008 && data != null
          && data.get("detail").asText().startsWith("state_")) {
        assertEquals(BatchProofException.Kind.UNAVAILABLE, typed.getKind());
        // by the contract only a pending state is worth another attempt
        assertEquals(data.get("detail").asText().equals("state_pending"), typed.isRetryable());
        assertNotNull(typed.getIndexes());
      }
      return;
    }
    assertFalse(failed[0]);
    boolean exact = params.has("stateRoot") && !"latest".equals(params.get("stateRoot").asText());
    JsonRpcAggregatorClient.BatchResult parsed =
        JsonRpcAggregatorClient.parseBatch(result, params.get("stateIds").size(), exact, 1, null);
    assertNull(parsed.waitedMillis);
    assertEquals(exact, parsed.viewHex == null, "a view comes with latest requests only");
  }
}
