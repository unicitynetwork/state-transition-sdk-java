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

  /** Constructor for tests that script the request ids of the transport. */
  JsonRpcAggregatorClient(JsonRpcHttpTransport transport, String apiKey) {
    this.transport = Objects.requireNonNull(transport, "transport cannot be null");
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
              if (!result.isObject() || !result.path("status").isTextual()) {
                throw new JsonRpcResponseException(
                        "Malformed result of certification_request: a status is required.");
              }
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

  /** The result of {@code get_inclusion_proof.v2} checked by shape, before the proof is decoded. */
  static final class ProofResult {
    final String proofHex;
    final ProofStatusHint statusHint;
    final Long waitedMillis;

    ProofResult(String proofHex, ProofStatusHint statusHint, Long waitedMillis) {
      this.proofHex = proofHex;
      this.statusHint = statusHint;
      this.waitedMillis = waitedMillis;
    }
  }

  private static InclusionProofResponse decodeInclusionProofResult(JsonNode result,
      boolean waitAsked) {
    ProofResult parsed = parseInclusionProofResult(result, waitAsked);
    return decodeProof(parsed.proofHex).withStatusHint(parsed.statusHint)
            .withWaitedMillis(parsed.waitedMillis);
  }

  static ProofResult parseInclusionProofResult(JsonNode result, boolean waitAsked) {
    // a plain string is the legacy result (and the answer of a server that ignores the flags): no
    // status, no echo
    if (result.isTextual()) {
      return new ProofResult(result.textValue(), null, null);
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
    return new ProofResult(result.get("proof").textValue(),
            status != null && status.isTextual()
                    ? ProofStatusHint.fromWire(status.textValue()) : null,
            echo == null ? null : echo.longValue());
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
      if (result.isDone()) {
        // the caller cancelled between the snapshot of the previous call and this one
        call.cancel(true);
        return;
      }
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

  /** A batch result checked by shape, before the proofs are decoded. */
  static final class BatchResult {
    final List<String> proofHex;
    final String viewHex;
    final Long waitedMillis;

    BatchResult(List<String> proofHex, String viewHex, Long waitedMillis) {
      this.proofHex = proofHex;
      this.viewHex = viewHex;
      this.waitedMillis = waitedMillis;
    }
  }

  private static InclusionProofBatch decodeBatch(JsonNode body, int count, boolean exact,
      int requests, Long waitAsked) {
    BatchResult parsed = parseBatch(body, count, exact, requests, waitAsked);
    try {
      List<InclusionProofResponse> decoded = new ArrayList<>(count);
      for (String hex : parsed.proofHex) {
        decoded.add(InclusionProofResponse.fromCbor(HexConverter.decode(hex)));
      }
      byte[] viewBytes = parsed.viewHex == null ? null : HexConverter.decode(parsed.viewHex);
      return new InclusionProofBatch(decoded, viewBytes, requests, parsed.waitedMillis);
    } catch (RuntimeException e) {
      throw invalid("Malformed batch result: " + e.getMessage(), e, requests);
    }
  }

  static BatchResult parseBatch(JsonNode body, int count, boolean exact, int requests,
      Long waitAsked) {
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
    List<String> hex = new ArrayList<>(count);
    for (JsonNode proof : proofs) {
      if (!proof.isTextual()) {
        throw invalid("Malformed batch result: proof must be a hex string.", null, requests);
      }
      hex.add(proof.textValue());
    }
    return new BatchResult(hex, view == null ? null : view.textValue(),
            echo == null ? null : echo.longValue());
  }

  private static BatchProofException invalid(String message, Throwable cause, int requests) {
    return batchError(BatchProofException.Kind.INVALID_RESPONSE, message, cause, null,
            null, null, requests, false, null);
  }

  static Throwable batchFailure(Throwable e, int requests, Long waitAsked) {
    if (e instanceof BatchProofException || e instanceof CancellationException
            || e instanceof TimeoutException) {
      return e;
    }
    if (e instanceof JsonRpcNetworkException
            && ((JsonRpcNetworkException) e).getRpcError() != null) {
      return batchFailure(((JsonRpcNetworkException) e).getRpcError(), requests, waitAsked);
    }
    if (e instanceof JsonRpcDataException) {
      if (!((JsonRpcDataException) e).isIdMatched()) {
        // an error that carries no id of this request answers nothing of ours: no classification
        return batchError(BatchProofException.Kind.INVALID_RESPONSE,
            "JSON-RPC error without the request id.", e, null, null, null, requests, false, null);
      }
      return rpcFailure((JsonRpcDataException) e, requests, waitAsked);
    }
    if (e instanceof JsonRpcResponseException) {
      return batchError(BatchProofException.Kind.INVALID_RESPONSE, e.getMessage(), e,
              null, null, null, requests, false, null);
    }
    if (e instanceof JsonRpcNetworkException) {
      int status = ((JsonRpcNetworkException) e).getStatus();
      return batchError(BatchProofException.Kind.TRANSPORT, "HTTP " + status, e, null,
              "http_" + status, null, requests,
              // the credential is missing or wrong: a new attempt with the same key cannot succeed
              status != 401 && status != 403, null);
    }
    return batchError(BatchProofException.Kind.TRANSPORT, String.valueOf(
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
      return batchError(BatchProofException.Kind.INVALID_RESPONSE,
              "Malformed waitedMs in the error data.", e, code, null, null, requests, false, null);
    }
    Long waited = waitAsked != null && echo != null ? echo.longValue() : null;
    switch (code) {
      case -32008: // proof unavailable
        if (BatchProofException.isStateDetail(detail)) {
          // by the contract only a pending state is worth another attempt, whatever else is said
          return batchError(BatchProofException.Kind.UNAVAILABLE, e.getErrorMessage(), e,
                  code, detail, stateIndexes(data, detail), requests, "state_pending".equals(detail),
                  waited);
        }
        return batchError("unsupported_configuration".equals(detail)
                ? BatchProofException.Kind.UNSUPPORTED : BatchProofException.Kind.UNAVAILABLE,
                e.getErrorMessage(), e, code, detail, null, requests,
                !"unsupported_configuration".equals(detail) && retryable, waited);
      case -32006: // concurrency limit: busy, with or without data
        return batchError(BatchProofException.Kind.UNAVAILABLE, e.getErrorMessage(), e,
                code, detail == null ? "busy" : "busy:" + detail, null, requests, true, waited);
      case -32601:
        return batchError(BatchProofException.Kind.UNSUPPORTED, e.getErrorMessage(), e,
                code, "method_not_found", null, requests, false, null);
      case -32602:
      case -32600:
        return batchError(BatchProofException.Kind.REJECTED, e.getErrorMessage(), e, code,
                detail, null, requests, false, waited);
      case -32007:
        return batchError(BatchProofException.Kind.LIMIT_EXCEEDED, e.getErrorMessage(), e,
                code, detail, null, requests, false, waited);
      default: // database, internal and unknown codes are operational failures
        return batchError(BatchProofException.Kind.SERVER, e.getErrorMessage(), e, code,
                detail, null, requests, retryable, waited);
    }
  }

  /**
   * The offending request indexes of the class a state status error names: {@code data.indexes} is
   * an object with one list per class ({@code pending}, {@code expired}, {@code unknown},
   * {@code not_in_root}). A malformed list is ignored, never trusted for control flow.
   */
  private static List<Integer> stateIndexes(JsonNode data, String detail) {
    JsonNode raw = data == null ? null : data.get("indexes");
    if (raw == null || !raw.isObject()) {
      return null;
    }
    JsonNode list = raw.get(detail.substring("state_".length()));
    if (list == null || !list.isArray() || list.size() > BATCH_MAX_STATE_IDS) {
      return null;
    }
    List<Integer> valid = new ArrayList<>();
    for (JsonNode index : list) {
      if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0) {
        return null;
      }
      valid.add(index.intValue());
    }
    return valid;
  }

  private static BatchProofException batchError(BatchProofException.Kind kind, String message,
      Throwable cause, Integer code, String detail, List<Integer> indexes, int requests,
      boolean retryable, Long waitedMillis) {
    return new BatchProofException(kind, message, code, detail, indexes, retryable, requests,
        waitedMillis, cause);
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
                  rpc.getErrorMessage(), e, rpc.getCode(), true);
        case -32022: // the claim, not an error
          return null;
        case -32023: // proof admission limit
          return new NonInclusionProofException(NonInclusionProofException.Kind.BUSY,
                  rpc.getErrorMessage(), e, rpc.getCode(), true);
        case -32600:
        case -32601:
        case -32602:
          return new NonInclusionProofException(NonInclusionProofException.Kind.REJECTED,
                  rpc.getErrorMessage(), e, rpc.getCode(), false);
        default: // internal and unknown codes are kept, not interpreted
          return new NonInclusionProofException(NonInclusionProofException.Kind.SERVER,
                  rpc.getErrorMessage(), e, rpc.getCode(), false);
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
