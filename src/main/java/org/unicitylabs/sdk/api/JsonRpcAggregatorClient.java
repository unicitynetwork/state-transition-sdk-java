package org.unicitylabs.sdk.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcDataException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcHttpTransport;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcNetworkException;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcResponseException;
import org.unicitylabs.sdk.internal.LongPoll;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.util.HexConverter;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;

/**
 * Default aggregator client over JSON-RPC and HTTP.
 *
 * <p>Besides the plain {@link AggregatorClient} calls it offers single calls with {@link
 * RequestOptions} ({@link StatusAwareAggregatorClient}), batch proofs ({@link
 * BatchAggregatorClient}) and non-inclusion proofs ({@link NonInclusionAggregatorClient}).
 * Cancelling a returned future cancels the in-flight HTTP call.
 */
public class JsonRpcAggregatorClient implements StatusAwareAggregatorClient,
    BatchAggregatorClient, NonInclusionAggregatorClient {
  private static final String STATE_ID_HEADER = "X-State-ID";
  /** Longest hex string of a non-inclusion proof: 128 KiB of proof, plus the prefix room. */
  private static final int MAX_NON_INCLUSION_HEX = 128 * 1024 * 2 + 2;
  private static final String[] BATCH_INDEX_CLASSES = {"expired", "not_in_root", "pending",
      "unknown"};

  private final JsonRpcHttpTransport transport;
  private final String apiKey;

  /**
   * Create aggregator client for destination url.
   *
   * @param url destination url
   */
  public JsonRpcAggregatorClient(String url) {
    this(url, null);
  }


  /**
   * Create aggregator client for destination url with api key. When an api key is supplied the
   * url must be {@code https}, so the key is never sent over plaintext.
   *
   * @param url    destination url
   * @param apiKey api key
   *
   * @throws IllegalArgumentException if an api key is supplied for a non-https url
   */
  public JsonRpcAggregatorClient(String url, String apiKey) {
    this(url, apiKey, false);
  }

  /**
   * Create aggregator client for destination url with api key.
   *
   * @param url    destination url
   * @param apiKey api key
   * @param allowInsecureTransport when {@code true}, permit sending the api key over a non-https
   *     url (intended for local development and testing only)
   *
   * @throws IllegalArgumentException if an api key is supplied for a non-https url and
   *     {@code allowInsecureTransport} is {@code false}
   */
  public JsonRpcAggregatorClient(String url, String apiKey, boolean allowInsecureTransport) {
    this(url, apiKey, allowInsecureTransport, null);
  }

  /**
   * Create aggregator client that shares a caller-supplied HTTP client, and with it the connection
   * pool and the dispatcher. The client's redirect following is switched off on the transport, and
   * the SDK never shuts the client's dispatcher down.
   *
   * @param url    destination url
   * @param apiKey api key, may be null
   * @param allowInsecureTransport when {@code true}, permit sending the api key over a non-https
   *     url (intended for local development and testing only)
   * @param httpClient OkHttp client to share, null for the SDK's default one
   *
   * @throws IllegalArgumentException if an api key is supplied for a non-https url and
   *     {@code allowInsecureTransport} is {@code false}
   */
  public JsonRpcAggregatorClient(String url, String apiKey, boolean allowInsecureTransport,
      OkHttpClient httpClient) {
    Objects.requireNonNull(url, "url cannot be null");

    if (apiKey != null && !allowInsecureTransport && !JsonRpcAggregatorClient.isHttps(url)) {
      throw new IllegalArgumentException(
              "API key must not be sent over plaintext HTTP; use an https url.");
    }

    this.transport = httpClient == null
            ? new JsonRpcHttpTransport(url)
            : new JsonRpcHttpTransport(url, httpClient);
    this.apiKey = apiKey;
  }

  private static boolean isHttps(String url) {
    String scheme = URI.create(url).getScheme();
    return scheme != null && scheme.toLowerCase(Locale.ROOT).equals("https");
  }

  private Map<String, List<String>> authHeaders() {
    Map<String, List<String>> headers = new HashMap<>();
    if (this.apiKey != null) {
      headers.put(AUTHORIZATION, List.of(String.format("Bearer %s", this.apiKey)));
    }
    return headers;
  }

  /**
   * Submit a certification request for a transaction state transition.
   *
   * @param certificationData certification payload
   *
   * @return asynchronous certification response
   */
  @Override
  public CompletableFuture<CertificationResponse> submitCertificationRequest(
          CertificationData certificationData
  ) {
    return this.submitCertificationRequest(certificationData, RequestOptions.none());
  }

  @Override
  public CompletableFuture<CertificationResponse> submitCertificationRequest(
          CertificationData certificationData, RequestOptions options
  ) {
    Objects.requireNonNull(options, "options cannot be null");
    if (options.getWaitMillis() != null) {
      throw new IllegalArgumentException("A submission cannot be waited on.");
    }
    CertificationRequest request = CertificationRequest.create(
            Objects.requireNonNull(certificationData, "certificationData cannot be null"));

    Map<String, List<String>> headers = this.authHeaders();
    headers.put(STATE_ID_HEADER, List.of(HexConverter.encode(request.getStateId().getData())));

    return LongPoll.map(
            this.transport.call("certification_request", HexConverter.encode(request.toCbor()),
                    headers, options),
            result -> {
              try {
                return UnicityObjectMapper.JSON.treeToValue(result, CertificationResponse.class);
              } catch (JsonProcessingException e) {
                throw new JsonRpcResponseException(
                        "Malformed result of certification_request: " + e.getOriginalMessage(), e);
              }
            });
  }

  /**
   * Get inclusion proof for state id.
   *
   * @param stateId state id
   * @return inclusion / non inclusion proof
   */
  @Override
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
    return this.getInclusionProof(stateId, RequestOptions.none());
  }

  @Override
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId,
      RequestOptions options) {
    Objects.requireNonNull(stateId, "stateId cannot be null");
    Objects.requireNonNull(options, "options cannot be null");

    ObjectNode params = UnicityObjectMapper.JSON.createObjectNode();
    params.put("stateId", HexConverter.encode(stateId.getData()));
    if (options.isStatusHint()) {
      params.put("includeStatus", true);
    }
    Long waitMillis = options.getWaitMillis();
    if (waitMillis != null) {
      params.put("waitMs", waitMillis);
    }

    return LongPoll.map(
            this.transport.call("get_inclusion_proof.v2", params, Map.of(), options),
            result -> decodeInclusionProofResult(result, waitMillis != null));
  }

  private static InclusionProofResponse decodeInclusionProofResult(JsonNode result,
      boolean waitAsked) {
    // a plain string is the legacy result (and the answer of a server that ignores the flags): no
    // status, no echo
    if (result.isTextual()) {
      return decodeProof(result.textValue());
    }
    if (!result.isObject() || !result.path("proof").isTextual()) {
      throw new JsonRpcResponseException("Unexpected result of get_inclusion_proof.v2.");
    }
    JsonNode echo = result.get("waitedMs");
    // the echo answers a wait request, nothing else, and is an integer or a protocol error
    if (echo != null && (!waitAsked || !LongPoll.isWireMillis(echo))) {
      throw new JsonRpcResponseException(
              "Unexpected or malformed waitedMs in the result of get_inclusion_proof.v2.");
    }
    // an unrecognized status value is treated like no status at all
    JsonNode status = result.get("status");
    return decodeProof(result.get("proof").textValue())
            .withStatusHint(status != null && status.isTextual()
                    ? ProofStatusHint.fromWire(status.textValue()) : null)
            .withWaitedMillis(echo == null ? null : echo.longValue());
  }

  /**
   * Any failure of decoding, whatever its type or text, is the phase "the service sent material
   * that is not a proof": a malformed response, never a transport or service condition.
   */
  private static InclusionProofResponse decodeProof(String hex) {
    try {
      return InclusionProofResponse.fromCbor(HexConverter.decode(hex));
    } catch (RuntimeException e) {
      throw new JsonRpcResponseException(
              "Malformed result of get_inclusion_proof.v2: " + e.getMessage(), e);
    }
  }

  /**
   * Get the latest block number.
   *
   * @return latest block number
   */
  @Override
  public CompletableFuture<Long> getLatestBlockNumber() {
    return this.transport.request("get_block_height", Map.of(), BlockHeightResponse.class)
            .thenApply(BlockHeightResponse::getBlockNumber);
  }

  // ---- batch -------------------------------------------------------------------------------

  @Override
  public CompletableFuture<InclusionProofBatch> getInclusionProofs(List<StateId> stateIds,
      BatchRequestOptions options) {
    Objects.requireNonNull(stateIds, "stateIds cannot be null");
    Objects.requireNonNull(options, "options cannot be null");
    if (stateIds.isEmpty() || stateIds.size() > BATCH_MAX_STATE_IDS) {
      throw new IllegalArgumentException("Batch must contain 1.." + BATCH_MAX_STATE_IDS
              + " state ids, got " + stateIds.size() + ".");
    }
    List<String> ids = new ArrayList<>(stateIds.size());
    Set<String> distinct = new HashSet<>();
    for (StateId stateId : stateIds) {
      String hex = HexConverter.encode(Objects.requireNonNull(stateId, "state id cannot be null")
              .getData());
      if (!distinct.add(hex)) {
        throw new IllegalArgumentException("Batch state ids must be distinct.");
      }
      ids.add(hex);
    }
    boolean exact = options.getStateRoot() != null;
    Long waitMillis = options.getWaitMillis();
    RequestOptions callOptions = RequestOptions.none();
    if (options.getOperation() != null) {
      callOptions = callOptions.withOperation(options.getOperation());
    }
    if (waitMillis != null) {
      callOptions = callOptions.withWaitMillis(waitMillis);
    }

    CompletableFuture<InclusionProofBatch> result = new CompletableFuture<>();
    BatchCall call = new BatchCall(ids, exact ? HexConverter.encode(
            options.getStateRoot().getData()) : "latest", exact, waitMillis, callOptions);
    call.start(result);
    result.whenComplete((value, error) -> {
      if (result.isCancelled()) {
        call.cancel();
      }
    });
    return result;
  }

  /** One batch call: the request, and the one retry without the wait where the contract allows. */
  private final class BatchCall {
    private final List<String> ids;
    private final String stateRoot;
    private final boolean exact;
    private final Long waitMillis;
    private final RequestOptions options;
    private volatile CompletableFuture<JsonNode> inFlight;

    BatchCall(List<String> ids, String stateRoot, boolean exact, Long waitMillis,
        RequestOptions options) {
      this.ids = ids;
      this.stateRoot = stateRoot;
      this.exact = exact;
      this.waitMillis = waitMillis;
      this.options = options;
    }

    void cancel() {
      CompletableFuture<JsonNode> current = this.inFlight;
      if (current != null) {
        current.cancel(true);
      }
    }

    void start(CompletableFuture<InclusionProofBatch> result) {
      this.send(this.waitMillis, 1, result);
    }

    private void send(Long wait, int requests, CompletableFuture<InclusionProofBatch> result) {
      ObjectNode params = UnicityObjectMapper.JSON.createObjectNode();
      ArrayNode array = params.putArray("stateIds");
      this.ids.forEach(array::add);
      params.put("stateRoot", wait == null ? this.stateRoot : "latest");
      if (wait != null) {
        params.put("waitMs", wait);
      }
      RequestOptions callOptions = wait == null && this.waitMillis != null
              ? withoutWait(this.options) : this.options;
      CompletableFuture<JsonNode> call = JsonRpcAggregatorClient.this.transport
              .call("get_inclusion_proofs.v1", params, JsonRpcAggregatorClient.this.authHeaders(),
                      callOptions);
      this.inFlight = call;
      call.whenComplete((value, error) -> {
        if (result.isDone()) {
          return;
        }
        if (error == null) {
          try {
            result.complete(decodeBatch(value, this.ids.size(), this.exact, requests, wait));
          } catch (RuntimeException e) {
            result.completeExceptionally(e);
          }
          return;
        }
        Throwable cause = unwrap(error);
        // A strict parser that does not know waitMs answers the generic malformed params without
        // naming the field. Repeat once without it, same other params and same operation (the
        // deadline is the caller's, never renewed); the second answer stands, whatever it is.
        // Nothing is cached: the next call tries the wait again.
        if (wait != null && requests == 1 && cause instanceof JsonRpcDataException
                && ((JsonRpcDataException) cause).isIdMatched()
                && LongPoll.isMalformedParams(((JsonRpcDataException) cause).getCode(),
                ((JsonRpcDataException) cause).getData())) {
          this.send(null, 2, result);
          return;
        }
        result.completeExceptionally(batchFailure(cause, requests, wait));
      });
    }
  }

  private static RequestOptions withoutWait(RequestOptions options) {
    RequestOptions copy = RequestOptions.none();
    if (options.getOperation() != null) {
      copy = copy.withOperation(options.getOperation());
    }
    if (options.getCallTimeout() != null) {
      copy = copy.withCallTimeout(options.getCallTimeout());
    }
    return copy;
  }

  private static Throwable unwrap(Throwable error) {
    return error instanceof java.util.concurrent.CompletionException && error.getCause() != null
            ? error.getCause() : error;
  }

  private static InclusionProofBatch decodeBatch(JsonNode body, int count, boolean exact,
      int requests, Long waitAsked) {
    if (body == null || !body.isObject()) {
      throw invalid("Batch result must be an object.", null, requests);
    }
    // Unknown result fields are ignored (the service may add fields), never copied into trusted
    // material: only the known fields below are read, and they stay strictly validated.
    JsonNode echo = body.get("waitedMs");
    if (echo != null && waitAsked == null) {
      throw invalid("Unexpected batch result field \"waitedMs\".", null, requests);
    }
    if (echo != null && !LongPoll.isWireMillis(echo)) {
      throw invalid("Malformed waitedMs: it must be an integer in [0, 2^53 - 1].", null, requests);
    }
    JsonNode proofs = body.get("proofs");
    if (proofs == null || !proofs.isArray() || proofs.size() != count) {
      throw invalid("Expected " + count + " proofs.", null, requests);
    }
    JsonNode view = body.get("partitionView");
    // an exact root request never carries a view: it is only meaningful for the latest seal
    if (view != null && (exact || !view.isTextual())) {
      throw invalid("Unexpected partition view.", null, requests);
    }
    try {
      List<InclusionProofResponse> decoded = new ArrayList<>(count);
      for (JsonNode proof : proofs) {
        if (!proof.isTextual()) {
          throw new IllegalArgumentException("Proof must be a hex string.");
        }
        decoded.add(InclusionProofResponse.fromCbor(HexConverter.decode(proof.textValue())));
      }
      byte[] viewBytes = view == null ? null : HexConverter.decode(view.textValue());
      return new InclusionProofBatch(decoded, viewBytes, requests,
              echo == null ? null : echo.longValue());
    } catch (RuntimeException e) {
      throw invalid("Malformed batch result: " + e.getMessage(), e, requests);
    }
  }

  private static BatchProofException invalid(String message, Throwable cause, int requests) {
    return new BatchProofException(BatchProofException.Kind.INVALID_RESPONSE, message, cause, null,
            null, null, requests, false, null);
  }

  private static Throwable batchFailure(Throwable e, int requests, Long waitAsked) {
    if (e instanceof BatchProofException || e instanceof CancellationException
            || e instanceof TimeoutException) {
      return e;
    }
    if (e instanceof JsonRpcNetworkException
            && ((JsonRpcNetworkException) e).getRpcError() != null) {
      return batchFailure(((JsonRpcNetworkException) e).getRpcError(), requests, waitAsked);
    }
    if (e instanceof JsonRpcDataException) {
      return rpcFailure((JsonRpcDataException) e, requests, waitAsked);
    }
    if (e instanceof JsonRpcResponseException) {
      return new BatchProofException(BatchProofException.Kind.INVALID_RESPONSE, e.getMessage(), e,
              null, null, null, requests, false, null);
    }
    if (e instanceof JsonRpcNetworkException) {
      int status = ((JsonRpcNetworkException) e).getStatus();
      return new BatchProofException(BatchProofException.Kind.TRANSPORT, "HTTP " + status, e, null,
              "http_" + status, null, requests,
              // the credential is missing or wrong: a new attempt with the same key cannot succeed
              status != 401 && status != 403, null);
    }
    return new BatchProofException(BatchProofException.Kind.TRANSPORT, String.valueOf(
            e.getMessage()), e, null, null, null, requests, true, null);
  }

  private static BatchProofException rpcFailure(JsonRpcDataException e, int requests,
      Long waitAsked) {
    JsonNode data = e.getData() != null && e.getData().isObject() ? e.getData() : null;
    String detail = data != null && data.path("detail").isTextual()
            ? data.path("detail").textValue() : null;
    boolean retryable = data != null && data.path("retryable").asBoolean(false);
    int code = e.getCode();
    JsonNode echo = data == null ? null : data.get("waitedMs");
    // the echo of a request that asked for a wait; a malformed one is a protocol error
    if (waitAsked != null && echo != null && !LongPoll.isWireMillis(echo)) {
      return new BatchProofException(BatchProofException.Kind.INVALID_RESPONSE,
              "Malformed waitedMs in the error data.", e, code, null, null, requests, false, null);
    }
    Long waited = waitAsked != null && echo != null ? echo.longValue() : null;
    switch (code) {
      case -32008: // proof unavailable
        if (isBatchStateDetail(detail)) {
          // by the contract only a pending state is worth another attempt, whatever else is said
          return new BatchProofException(BatchProofException.Kind.UNAVAILABLE, e.getMessage(), e,
                  code, detail, stateIndexes(data), requests, "state_pending".equals(detail),
                  waited);
        }
        return new BatchProofException("unsupported_configuration".equals(detail)
                ? BatchProofException.Kind.UNSUPPORTED : BatchProofException.Kind.UNAVAILABLE,
                e.getMessage(), e, code, detail, null, requests,
                !"unsupported_configuration".equals(detail) && retryable, waited);
      case -32006: // concurrency limit: busy, with or without data
        return new BatchProofException(BatchProofException.Kind.UNAVAILABLE, e.getMessage(), e,
                code, detail == null ? "busy" : "busy:" + detail, null, requests, true, waited);
      case -32601:
        return new BatchProofException(BatchProofException.Kind.UNSUPPORTED, e.getMessage(), e,
                code, "method_not_found", null, requests, false, null);
      case -32602:
      case -32600:
        return new BatchProofException(BatchProofException.Kind.REJECTED, e.getMessage(), e, code,
                detail, null, requests, false, waited);
      case -32007:
        return new BatchProofException(BatchProofException.Kind.LIMIT_EXCEEDED, e.getMessage(), e,
                code, detail, null, requests, false, waited);
      default: // database, internal and unknown codes are operational failures
        return new BatchProofException(BatchProofException.Kind.SERVER, e.getMessage(), e, code,
                detail, null, requests, retryable, waited);
    }
  }

  private static boolean isBatchStateDetail(String detail) {
    return "state_expired".equals(detail) || "state_not_in_root".equals(detail)
            || "state_pending".equals(detail) || "state_unknown".equals(detail);
  }

  /**
   * The offending request indexes of a state status error: {@code data.indexes} is an object with
   * one list per class. Malformed lists are ignored, never trusted for control flow.
   */
  private static Map<String, List<Integer>> stateIndexes(JsonNode data) {
    JsonNode raw = data == null ? null : data.get("indexes");
    if (raw == null || !raw.isObject()) {
      return null;
    }
    Map<String, List<Integer>> classes = new LinkedHashMap<>();
    int total = 0;
    for (String klass : BATCH_INDEX_CLASSES) {
      JsonNode list = raw.get(klass);
      if (list == null || !list.isArray()) {
        continue;
      }
      List<Integer> valid = new ArrayList<>();
      for (JsonNode index : list) {
        if (index.isIntegralNumber() && index.canConvertToInt() && index.intValue() >= 0) {
          valid.add(index.intValue());
        }
      }
      total += list.size();
      if (valid.size() == list.size() && total <= BATCH_MAX_STATE_IDS) {
        classes.put(klass, valid);
      }
    }
    return classes.isEmpty() ? null : classes;
  }

  // ---- non-inclusion -----------------------------------------------------------------------

  @Override
  public CompletableFuture<NonInclusionProofResponse> getNonInclusionProof(StateId stateId,
      RequestOptions options) {
    Objects.requireNonNull(stateId, "stateId cannot be null");
    Objects.requireNonNull(options, "options cannot be null");
    if (options.getWaitMillis() != null) {
      throw new IllegalArgumentException("A wait is not allowed for a non-inclusion proof.");
    }
    ObjectNode params = UnicityObjectMapper.JSON.createObjectNode();
    params.put("stateId", HexConverter.encode(stateId.getData()));

    CompletableFuture<JsonNode> call =
            this.transport.call("get_non_inclusion_proof.v1", params, Map.of(), options);
    CompletableFuture<NonInclusionProofResponse> result = new CompletableFuture<>();
    call.whenComplete((value, error) -> {
      if (error != null) {
        NonInclusionProofException failure = classifyNonInclusionFailure(unwrap(error), options);
        if (failure == null) {
          result.complete(NonInclusionProofResponse.includedHint());
        } else {
          result.completeExceptionally(failure);
        }
        return;
      }
      // the result is one hex string; its size is bounded before anything is decoded
      if (!value.isTextual() || value.textValue().length() > MAX_NON_INCLUSION_HEX) {
        result.completeExceptionally(new NonInclusionProofException(
                NonInclusionProofException.Kind.INVALID_RESPONSE,
                "The result must be a bounded hex string.", null, null, false));
        return;
      }
      try {
        result.complete(NonInclusionProofResponse.absence(
                new NonInclusionProof(stateId, HexConverter.decode(value.textValue()))));
      } catch (RuntimeException e) {
        result.completeExceptionally(new NonInclusionProofException(
                NonInclusionProofException.Kind.INVALID_RESPONSE,
                "Malformed non-inclusion proof: " + e.getMessage(), e, null, false));
      }
    });
    result.whenComplete((value, error) -> {
      if (result.isCancelled()) {
        call.cancel(true);
      }
    });
    return result;
  }

  /** A failure of the call, or null for the service's claim that the state is included. */
  private static NonInclusionProofException classifyNonInclusionFailure(Throwable e,
      RequestOptions options) {
    if (e instanceof NonInclusionProofException) {
      return (NonInclusionProofException) e;
    }
    if (e instanceof TimeoutException) {
      return new NonInclusionProofException(NonInclusionProofException.Kind.TIMEOUT,
              "The deadline of the request expired.", e, null, true);
    }
    if (e instanceof CancellationException) {
      return new NonInclusionProofException(NonInclusionProofException.Kind.CANCELLED,
              "The request was cancelled.", e, null, false);
    }
    if (e instanceof JsonRpcDataException) {
      JsonRpcDataException rpc = (JsonRpcDataException) e;
      // a code in an envelope that did not carry our request id answers nothing
      if (!rpc.isIdMatched()) {
        return new NonInclusionProofException(NonInclusionProofException.Kind.INVALID_RESPONSE,
                "JSON-RPC error without the request id.", e, null, false);
      }
      switch (rpc.getCode()) {
        case -32020: // no certified snapshot yet
          return new NonInclusionProofException(NonInclusionProofException.Kind.UNAVAILABLE,
                  rpc.getMessage(), e, rpc.getCode(), true);
        case -32022: // the claim, not an error
          return null;
        case -32023: // proof admission limit
          return new NonInclusionProofException(NonInclusionProofException.Kind.BUSY,
                  rpc.getMessage(), e, rpc.getCode(), true);
        case -32600:
        case -32601:
        case -32602:
          return new NonInclusionProofException(NonInclusionProofException.Kind.REJECTED,
                  rpc.getMessage(), e, rpc.getCode(), false);
        default: // internal and unknown codes are kept, not interpreted
          return new NonInclusionProofException(NonInclusionProofException.Kind.SERVER,
                  rpc.getMessage(), e, rpc.getCode(), false);
      }
    }
    if (e instanceof JsonRpcResponseException) {
      return new NonInclusionProofException(NonInclusionProofException.Kind.INVALID_RESPONSE,
              e.getMessage(), e, null, false);
    }
    if (e instanceof JsonRpcNetworkException) {
      JsonRpcNetworkException network = (JsonRpcNetworkException) e;
      // the service's own error survives any HTTP status; a body that is not a matching envelope
      // is transport
      return network.getRpcError() != null
              ? classifyNonInclusionFailure(network.getRpcError(), options)
              : new NonInclusionProofException(NonInclusionProofException.Kind.TRANSPORT,
                      "HTTP " + network.getStatus(), e, null, true);
    }
    return new NonInclusionProofException(NonInclusionProofException.Kind.TRANSPORT,
            String.valueOf(e.getMessage()), e, null, true);
  }
}
