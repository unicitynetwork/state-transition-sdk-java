package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import org.unicitylabs.sdk.api.jsonrpc.TestTransports;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/** Shared helpers of the wire tests: a client over a MockWebServer with scripted request ids. */
final class WireTestSupport {

  static final ObjectMapper MAPPER = new ObjectMapper();

  private WireTestSupport() {
  }

  static JsonRpcAggregatorClient client(MockWebServer server, OkHttpClient http,
      Supplier<JsonNode> ids) {
    return new JsonRpcAggregatorClient(
        TestTransports.withIds(server.url("/").toString(), http, 8 * 1024 * 1024, ids), null);
  }

  static JsonRpcAggregatorClient client(MockWebServer server, OkHttpClient http, int id) {
    return client(server, http, () -> IntNode.valueOf(id));
  }

  static StateId stateId(String hex) {
    return StateId.fromCbor(CborSerializer.encodeByteString(HexConverter.decode(hex)));
  }

  static Throwable failure(CompletableFuture<?> future) {
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

  static <T> T get(CompletableFuture<T> future) throws Exception {
    return future.get(5, TimeUnit.SECONDS);
  }

  static void assertRejected(Runnable call) {
    assertThrows(IllegalArgumentException.class, call::run);
  }
}
