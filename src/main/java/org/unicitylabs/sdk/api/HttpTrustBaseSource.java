package org.unicitylabs.sdk.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseRequest;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseSource;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcHttpTransport;
import org.unicitylabs.sdk.api.jsonrpc.SdkVersionHeader;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * The aggregator's trust base endpoint, {@code GET
 * /api/v1/trustbases?from=E&maxEntries=N[&snapshotEnd=E&snapshotEntryId=0x..]} on its origin
 * (whatever path the JSON-RPC URL has): the only delivery that exists. The body is the tag-39015
 * response, size-capped; nothing in it is trusted (see {@link TrustBaseSource}).
 *
 * <p>The same rules as the JSON-RPC calls apply: redirects are never followed, an API key is sent
 * as a bearer token only over HTTPS unless insecure transport was allowed explicitly, and the
 * {@code X-SDK-Version} header follows the {@link SdkVersionHeader} setting.
 */
public final class HttpTrustBaseSource implements TrustBaseSource {

  /** Largest response body read: a full page (1 MiB) plus slack. */
  public static final int MAX_RESPONSE_BYTES = (1 << 20) + 1024;

  /** Default budget of one request. */
  public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(30);

  private static final String PATH = "/api/v1/trustbases";

  private final HttpUrl endpoint;
  private final String apiKey;
  private final OkHttpClient httpClient;
  private final SdkVersionHeader sdkVersionHeader;

  /**
   * Create a source of an aggregator's trust bases.
   *
   * @param aggregatorUrl the aggregator's (JSON-RPC) URL; only its origin is used
   * @param apiKey api key, may be null
   * @param allowInsecureTransport allow an API key over plain HTTP
   * @param httpClient OkHttp client to use, or null for the SDK default
   * @param sdkVersionHeader what to send in the {@code X-SDK-Version} header
   */
  public HttpTrustBaseSource(String aggregatorUrl, String apiKey, boolean allowInsecureTransport,
      OkHttpClient httpClient, SdkVersionHeader sdkVersionHeader) {
    Objects.requireNonNull(aggregatorUrl, "aggregatorUrl cannot be null");
    this.sdkVersionHeader = Objects.requireNonNull(sdkVersionHeader,
        "sdkVersionHeader cannot be null");
    HttpUrl parsed = HttpUrl.parse(aggregatorUrl);
    if (parsed == null) {
      throw new IllegalArgumentException("Invalid aggregator URL.");
    }
    if (apiKey != null && !allowInsecureTransport && !isHttps(aggregatorUrl)) {
      throw new IllegalArgumentException(
          "An API key is only sent over HTTPS; pass allowInsecureTransport to override.");
    }
    this.endpoint = parsed.newBuilder().encodedPath(PATH).query(null).fragment(null).build();
    this.apiKey = apiKey;
    // Redirects are never followed, so the credentials are never replayed to another host.
    this.httpClient = (httpClient == null ? JsonRpcHttpTransport.defaultHttpClient() : httpClient)
        .newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(DEFAULT_CALL_TIMEOUT)
        .build();
  }

  /**
   * Create a source with the default client and SDK version header.
   *
   * @param aggregatorUrl the aggregator's (JSON-RPC) URL; only its origin is used
   * @param apiKey api key, may be null
   */
  public HttpTrustBaseSource(String aggregatorUrl, String apiKey) {
    this(aggregatorUrl, apiKey, false, null, SdkVersionHeader.sdkDefault());
  }

  private static boolean isHttps(String url) {
    try {
      String scheme = new URI(url).getScheme();
      return scheme != null && scheme.toLowerCase(Locale.ROOT).equals("https");
    } catch (URISyntaxException e) {
      return false;
    }
  }

  @Override
  public CompletableFuture<byte[]> getTrustBasePage(TrustBaseRequest request) {
    Objects.requireNonNull(request, "request cannot be null");
    HttpUrl.Builder url = this.endpoint.newBuilder()
        .addQueryParameter("from", Long.toUnsignedString(request.getFromEpoch()))
        .addQueryParameter("maxEntries", Integer.toString(request.getMaxEntries()));
    TrustBaseRequest.Snapshot snapshot = request.getSnapshot();
    if (snapshot != null) {
      url.addQueryParameter("snapshotEnd", Long.toUnsignedString(snapshot.getEnd()))
          .addQueryParameter("snapshotEntryId", "0x" + HexConverter.encode(snapshot.getEntryId()));
    }
    Request.Builder builder = new Request.Builder().url(url.build()).get()
        .header("Accept", "application/cbor");
    if (this.sdkVersionHeader.value() != null) {
      builder.header(SdkVersionHeader.NAME, this.sdkVersionHeader.value());
    }
    if (this.apiKey != null) {
      builder.header("Authorization", "Bearer " + this.apiKey);
    }

    CompletableFuture<byte[]> future = new CompletableFuture<>();
    Call call = this.httpClient.newCall(builder.build());
    future.whenComplete((result, error) -> {
      if (future.isCancelled()) {
        call.cancel();
      }
    });
    call.enqueue(new Callback() {
      @Override
      public void onFailure(Call call, IOException e) {
        if (call.isCanceled()) {
          CancellationException cancelled = new CancellationException("Request cancelled.");
          cancelled.initCause(e);
          future.completeExceptionally(cancelled);
        } else {
          future.completeExceptionally(e);
        }
      }

      @Override
      public void onResponse(Call call, Response response) {
        try (ResponseBody body = response.body()) {
          // A redirect or any other non-success status is a refusal, not a page.
          if (!response.isSuccessful()) {
            future.completeExceptionally(
                new IOException("HTTP " + response.code() + " from the trust base endpoint"));
            return;
          }
          future.complete(readBounded(body));
        } catch (Exception e) {
          future.completeExceptionally(e);
        }
      }
    });
    return future;
  }

  private static byte[] readBounded(ResponseBody body) throws IOException {
    if (body == null) {
      return new byte[0];
    }
    try (InputStream in = body.byteStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      long total = 0;
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > MAX_RESPONSE_BYTES) {
          throw new IOException("The trust base response exceeds the size cap.");
        }
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }
}
