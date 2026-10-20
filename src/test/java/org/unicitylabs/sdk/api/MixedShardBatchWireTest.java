package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gateway answers a batch that spans several shards with HTTP 400 and the unchanged JSON-RPC
 * -32602 body and id (exception D of the load balancer decision). The batch classifier reads the
 * RPC error behind the status and keeps its classification.
 */
class MixedShardBatchWireTest {

  private static final String WRONG_SHARD = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,"
      + "\"message\":\"Invalid params\",\"data\":{\"reason\":\"invalid_request\","
      + "\"detail\":\"wrong_shard\",\"retryable\":false}},\"id\":1}";
  private static final String MALFORMED = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,"
      + "\"message\":\"Invalid params\",\"data\":{\"reason\":\"invalid_request\","
      + "\"detail\":\"malformed_params\",\"retryable\":false}},\"id\":1}";

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

  private static List<StateId> ids() {
    return List.of(WireTestSupport.stateId("ab".repeat(32)), WireTestSupport.stateId("cd".repeat(32)));
  }

  @Test
  void aMixedShardAnswerBehindHttp400IsRejectedAsBeforeAndNeverRetriedWithoutTheWait() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(400).setBody(WRONG_SHARD));
    BatchProofException failure = assertInstanceOf(BatchProofException.class,
        WireTestSupport.failure(WireTestSupport.client(server, http, 1)
            .getInclusionProofs(ids(), BatchRequestOptions.latest().withWaitMillis(5_000))));

    assertEquals(BatchProofException.Kind.REJECTED, failure.getKind());
    assertEquals(-32602, failure.getCode());
    assertEquals("wrong_shard", failure.getDetail());
    assertFalse(failure.isRetryable());
    assertEquals(1, failure.getRequests());
    assertEquals(1, server.getRequestCount(), "not the retry-without-waitMs case");
  }

  @Test
  void theSameAnswerOnHttp200ClassifiesIdentically() throws Exception {
    server.enqueue(new MockResponse().setBody(WRONG_SHARD));
    BatchProofException failure = assertInstanceOf(BatchProofException.class,
        WireTestSupport.failure(WireTestSupport.client(server, http, 1)
            .getInclusionProofs(ids(), BatchRequestOptions.latest().withWaitMillis(5_000))));
    assertEquals(BatchProofException.Kind.REJECTED, failure.getKind());
    assertEquals("wrong_shard", failure.getDetail());
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void onlyTheBodyNotTheStatusDecidesTheRetryWithoutTheWait() throws Exception {
    // the body says malformed params: the one retry without the wait applies behind a 400 too
    server.enqueue(new MockResponse().setResponseCode(400).setBody(MALFORMED));
    server.enqueue(new MockResponse().setResponseCode(400).setBody(MALFORMED));
    BatchProofException failure = assertInstanceOf(BatchProofException.class,
        WireTestSupport.failure(WireTestSupport.client(server, http, 1)
            .getInclusionProofs(ids(), BatchRequestOptions.latest().withWaitMillis(5_000))));
    assertEquals(2, server.getRequestCount());
    assertEquals(2, failure.getRequests());
    assertEquals(BatchProofException.Kind.REJECTED, failure.getKind());
    assertTrue(failure.getMessage().length() > 0);
  }

  @Test
  void aBodyThatIsNotOursBehindHttp400IsATransportFailure() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(400).setBody("<html>Bad Request</html>"));
    BatchProofException failure = assertInstanceOf(BatchProofException.class,
        WireTestSupport.failure(WireTestSupport.client(server, http, 1)
            .getInclusionProofs(ids(), BatchRequestOptions.latest())));
    assertEquals(BatchProofException.Kind.TRANSPORT, failure.getKind());
    assertEquals("http_400", failure.getDetail());
  }
}
