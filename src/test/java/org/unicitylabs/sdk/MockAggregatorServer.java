package org.unicitylabs.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MockAggregatorServer {

  private final MockWebServer server;
  private final ObjectMapper objectMapper;
  private final Set<String> protectedMethods;
  private volatile boolean simulateRateLimit = false;
  private volatile int rateLimitRetryAfter = 0;
  private volatile String expectedApiKey = null;

  public MockAggregatorServer() {
    this.server = new MockWebServer();
    this.objectMapper = new ObjectMapper();
    this.protectedMethods = new HashSet<>();
    this.protectedMethods.add("certification_request");

    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return handleRequest(request);
      }
    });
  }

  public void start() throws IOException {
    server.start();
  }

  public void shutdown() throws IOException {
    server.shutdown();
  }

  public String getUrl() {
    return server.url("/").toString();
  }

  public RecordedRequest takeRequest() throws InterruptedException {
    return server.takeRequest();
  }

  public void simulateRateLimitForNextRequest(int retryAfterSeconds) {
    this.simulateRateLimit = true;
    this.rateLimitRetryAfter = retryAfterSeconds;
  }

  public void setExpectedApiKey(String apiKey) {
    this.expectedApiKey = apiKey;
  }

  private MockResponse handleRequest(RecordedRequest request) {
    try {
      if (simulateRateLimit) {
        try {
          return new MockResponse()
                  .setResponseCode(429)
                  .setHeader("Retry-After", String.valueOf(rateLimitRetryAfter))
                  .setBody("Too Many Requests");
        } finally {
          // Reset for next request
          simulateRateLimit = false;
          rateLimitRetryAfter = 0;
        }
      }

      String method = extractJsonRpcMethod(request);

      if (protectedMethods.contains(method) && expectedApiKey != null && !hasValidApiKey(request)) {
        return new MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", "Bearer")
                .setBody("Unauthorized");
      }

      return generateSuccessResponse(method);

    } catch (Exception e) {
      return new MockResponse()
              .setResponseCode(400)
              .setBody("Bad Request");
    }
  }

  private boolean hasValidApiKey(RecordedRequest request) {
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      String providedKey = authHeader.substring(7);
      return expectedApiKey.equals(providedKey);
    }
    return false;
  }

  private @Nullable String extractJsonRpcMethod(RecordedRequest request) throws JsonProcessingException {
    if (!"POST".equals(request.getMethod())) {
      return null;
    }
    JsonNode jsonRequest = objectMapper.readTree(request.getBody().readUtf8());
    return jsonRequest.has("method") ? jsonRequest.get("method").asText() : null;
  }

  private MockResponse generateSuccessResponse(String method) {
    String responseBody;
    String id = UUID.randomUUID().toString();

    switch (method != null ? method : "") {
      case "certification_request":
        responseBody = String.format(
                "{\n" +
                        "    \"jsonrpc\": \"2.0\",\n" +
                        "    \"result\": {\n" +
                        "        \"status\": \"SUCCESS\"\n" +
                        "    },\n" +
                        "    \"id\": \"%s\"\n" +
                        "}", id);
        break;

      case "get_block_height":
        responseBody = String.format(
                "{\n" +
                        "    \"jsonrpc\": \"2.0\",\n" +
                        "    \"result\": {\n" +
                        "        \"blockNumber\": \"67890\"\n" +
                        "    },\n" +
                        "    \"id\": \"%s\"\n" +
                        "}", id);
        break;

      default:
        responseBody = String.format(
                "{\n" +
                        "    \"jsonrpc\": \"2.0\",\n" +
                        "    \"result\": \"OK\",\n" +
                        "    \"id\": \"%s\"\n" +
                        "}", id);
        break;
    }

    return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(responseBody);
  }
}