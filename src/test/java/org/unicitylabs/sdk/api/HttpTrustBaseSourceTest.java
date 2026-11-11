package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseRequest;
import org.unicitylabs.sdk.api.jsonrpc.SdkVersionHeader;

/** The HTTP source of trust base pages, against a mock aggregator. */
class HttpTrustBaseSourceTest {

  private MockWebServer server;

  @BeforeEach
  void start() throws IOException {
    this.server = new MockWebServer();
    this.server.start();
  }

  @AfterEach
  void stop() throws IOException {
    this.server.shutdown();
  }

  private HttpTrustBaseSource source(String apiKey, SdkVersionHeader header) {
    return new HttpTrustBaseSource(this.server.url("/rpc/path").toString(), apiKey, true, null,
        header);
  }

  private static TrustBaseRequest request(TrustBaseRequest.Snapshot snapshot) {
    return new TrustBaseRequest(5, 64, NetworkId.TESTNET2, snapshot);
  }

  private static byte[] get(CompletableFuture<byte[]> f) throws Exception {
    return f.get(5, TimeUnit.SECONDS);
  }

  @Test
  void theRequestGoesToTheOriginsTrustBaseEndpoint() throws Exception {
    this.server.enqueue(new MockResponse().setBody(new Buffer().write(new byte[] {1, 2, 3})));
    byte[] page = get(source(null, SdkVersionHeader.sdkDefault()).getTrustBasePage(request(null)));
    assertArrayEquals(new byte[] {1, 2, 3}, page);

    RecordedRequest r = this.server.takeRequest();
    assertEquals("GET", r.getMethod());
    assertEquals("/api/v1/trustbases", r.getRequestUrl().encodedPath());
    assertEquals("5", r.getRequestUrl().queryParameter("from"));
    assertEquals("64", r.getRequestUrl().queryParameter("maxEntries"));
    assertNull(r.getRequestUrl().queryParameter("snapshotEnd"));
    assertNull(r.getRequestUrl().queryParameter("snapshotEntryId"));
    assertEquals("application/cbor", r.getHeader("Accept"));
    assertTrue(r.getHeader("X-SDK-Version").startsWith("java/"));
    assertNull(r.getHeader("Authorization"));
  }

  @Test
  void theSnapshotIsEchoedAndEpochsArePrintedUnsigned() throws Exception {
    this.server.enqueue(new MockResponse().setBody("x"));
    byte[] id = new byte[32];
    id[0] = (byte) 0xab;
    id[31] = 0x01;
    TrustBaseRequest.Snapshot snapshot = new TrustBaseRequest.Snapshot(-1L, id);
    get(source(null, SdkVersionHeader.sdkDefault())
        .getTrustBasePage(new TrustBaseRequest(-2L, 8, NetworkId.TESTNET2, snapshot)));

    RecordedRequest r = this.server.takeRequest();
    assertEquals("18446744073709551614", r.getRequestUrl().queryParameter("from"));
    assertEquals("18446744073709551615", r.getRequestUrl().queryParameter("snapshotEnd"));
    assertEquals("0xab00000000000000000000000000000000000000000000000000000000000001",
        r.getRequestUrl().queryParameter("snapshotEntryId"));
  }

  @Test
  void theVersionHeaderIsDefaultOverriddenOrOff() throws Exception {
    for (int i = 0; i < 3; i++) {
      this.server.enqueue(new MockResponse().setBody("x"));
    }
    get(source(null, SdkVersionHeader.of("wallet/7")).getTrustBasePage(request(null)));
    assertEquals("wallet/7", this.server.takeRequest().getHeader("X-SDK-Version"));
    get(source(null, SdkVersionHeader.disabled()).getTrustBasePage(request(null)));
    assertNull(this.server.takeRequest().getHeader("X-SDK-Version"));
    get(source(null, SdkVersionHeader.sdkDefault()).getTrustBasePage(request(null)));
    assertTrue(this.server.takeRequest().getHeader("X-SDK-Version").startsWith("java/"));
  }

  @Test
  void theApiKeyIsSentAsTheJsonRpcCallsSendIt() throws Exception {
    this.server.enqueue(new MockResponse().setBody("x"));
    get(source("k-123", SdkVersionHeader.sdkDefault()).getTrustBasePage(request(null)));
    assertEquals("Bearer k-123", this.server.takeRequest().getHeader("Authorization"));
  }

  @Test
  void anApiKeyOverPlainHttpIsRefusedUnlessAllowed() {
    String url = this.server.url("/").toString();
    assertThrows(IllegalArgumentException.class, () -> new HttpTrustBaseSource(url, "k"));
  }

  @Test
  void aRedirectIsARefusalAndNothingIsFollowed() throws Exception {
    this.server.enqueue(new MockResponse().setResponseCode(302)
        .setHeader("Location", this.server.url("/elsewhere").toString()));
    ExecutionException e = assertThrows(ExecutionException.class,
        () -> get(source("k", SdkVersionHeader.sdkDefault()).getTrustBasePage(request(null))));
    assertInstanceOf(IOException.class, e.getCause());
    assertTrue(e.getCause().getMessage().contains("302"));
    assertEquals(1, this.server.getRequestCount());
  }

  @Test
  void aNonSuccessStatusIsARefusalWithItsCode() {
    this.server.enqueue(new MockResponse().setResponseCode(503).setBody("busy"));
    ExecutionException e = assertThrows(ExecutionException.class,
        () -> get(source(null, SdkVersionHeader.sdkDefault()).getTrustBasePage(request(null))));
    assertTrue(e.getCause().getMessage().contains("503"));
  }

  @Test
  void theBodyIsReadUpToTheCapAndNoFurther() throws Exception {
    this.server.enqueue(new MockResponse()
        .setBody(new Buffer().write(new byte[HttpTrustBaseSource.MAX_RESPONSE_BYTES])));
    assertEquals(HttpTrustBaseSource.MAX_RESPONSE_BYTES,
        get(source(null, SdkVersionHeader.sdkDefault()).getTrustBasePage(request(null))).length);

    this.server.enqueue(new MockResponse()
        .setBody(new Buffer().write(new byte[HttpTrustBaseSource.MAX_RESPONSE_BYTES + 1])));
    ExecutionException e = assertThrows(ExecutionException.class,
        () -> get(source(null, SdkVersionHeader.sdkDefault()).getTrustBasePage(request(null))));
    assertTrue(e.getCause().getMessage().contains("size cap"));
  }

  @Test
  void cancellingTheFutureCancelsTheCall() throws Exception {
    this.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    CompletableFuture<byte[]> f = source(null, SdkVersionHeader.sdkDefault())
        .getTrustBasePage(request(null));
    this.server.takeRequest(5, TimeUnit.SECONDS);
    assertTrue(f.cancel(true));
    assertThrows(CancellationException.class, () -> f.get(1, TimeUnit.SECONDS));
  }
}
