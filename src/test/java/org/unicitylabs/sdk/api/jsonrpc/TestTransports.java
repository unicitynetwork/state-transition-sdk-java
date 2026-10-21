package org.unicitylabs.sdk.api.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.OkHttpClient;

import java.util.function.Supplier;

/** Gives tests of other packages the transport with scripted request ids. */
public final class TestTransports {

  private TestTransports() {
  }

  public static JsonRpcHttpTransport withIds(String url, OkHttpClient client, int maxResponseBytes,
      Supplier<JsonNode> ids) {
    return new JsonRpcHttpTransport(url, client, maxResponseBytes, ids);
  }
}
