package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.SdkVersion;
import org.unicitylabs.sdk.api.jsonrpc.SdkVersionHeader;

/** The X-SDK-Version header on the wire: default, custom value, disabled, on every call. */
class SdkVersionHeaderWireTest {

  private static final String NAME = "X-SDK-Version";
  private static final StateId STATE_ID = WireTestSupport.stateId("00".repeat(32));

  private MockWebServer server;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void stop() throws Exception {
    server.shutdown();
  }

  private RecordedRequest sent(JsonRpcAggregatorClient client,
      Function<JsonRpcAggregatorClient, java.util.concurrent.CompletableFuture<?>> call)
      throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));
    WireTestSupport.failure(call.apply(client));
    RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(request);
    return request;
  }

  private JsonRpcAggregatorClient clientWith(SdkVersionHeader header) {
    return new JsonRpcAggregatorClient(server.url("/").toString(), null, true,
        new OkHttpClient(), header);
  }

  @Test
  void versionIsTheGradleProjectVersion() throws Exception {
    String build = new String(Files.readAllBytes(Paths.get("build.gradle.kts")),
        java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(build.contains("generateSdkVersion"));
    assertNotNull(SdkVersion.VERSION);
    assertTrue(!SdkVersion.VERSION.isEmpty());
    assertEquals("java/" + SdkVersion.VERSION, SdkVersionHeader.sdkDefault().value());
  }

  @Test
  void defaultHeaderIsSentOnEveryCall() throws Exception {
    JsonRpcAggregatorClient client =
        new JsonRpcAggregatorClient(server.url("/").toString());
    String expected = "java/" + SdkVersion.VERSION;
    assertEquals(expected,
        sent(client, JsonRpcAggregatorClient::getLatestBlockNumber).getHeader(NAME));
    assertEquals(expected, sent(client, c -> c.getInclusionProof(STATE_ID)).getHeader(NAME));
    assertEquals(expected,
        sent(client, c -> c.getInclusionProofs(List.of(STATE_ID), BatchRequestOptions.latest())).getHeader(NAME));
  }

  @Test
  void aCustomValueReplacesTheDefault() throws Exception {
    JsonRpcAggregatorClient client = clientWith(SdkVersionHeader.of("wallet/7"));
    assertEquals("wallet/7",
        sent(client, JsonRpcAggregatorClient::getLatestBlockNumber).getHeader(NAME));
    assertEquals("wallet/7", sent(client, c -> c.getInclusionProof(STATE_ID)).getHeader(NAME));
  }

  @Test
  void disabledSendsNoHeader() throws Exception {
    JsonRpcAggregatorClient client = clientWith(SdkVersionHeader.disabled());
    assertNull(sent(client, JsonRpcAggregatorClient::getLatestBlockNumber).getHeader(NAME));
    assertNull(sent(client, c -> c.getInclusionProof(STATE_ID)).getHeader(NAME));
  }

  @Test
  void aValueThatIsNotPrintableAsciiIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> SdkVersionHeader.of(""));
    assertThrows(IllegalArgumentException.class, () -> SdkVersionHeader.of("a\nb"));
    assertThrows(IllegalArgumentException.class, () -> SdkVersionHeader.of("café"));
    assertThrows(NullPointerException.class, () -> SdkVersionHeader.of(null));
  }
}
