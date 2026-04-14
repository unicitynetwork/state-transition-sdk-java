package org.unicitylabs.sdk.api;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcHttpTransport;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Default aggregator client.
 */
public class JsonRpcAggregatorClient implements AggregatorClient {

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
   * Create aggregator client for destination url with api key.
   *
   * @param url    destination url
   * @param apiKey api key
   *
   */
  public JsonRpcAggregatorClient(String url, String apiKey) {
    this.transport = new JsonRpcHttpTransport(Objects.requireNonNull(url, "url cannot be null"));
    this.apiKey = apiKey;
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
    CertificationRequest request = CertificationRequest.create(
        Objects.requireNonNull(certificationData, "certificationData cannot be null"));

    Map<String, List<String>> headers = this.apiKey == null
        ? Map.of()
        : Map.of(AUTHORIZATION, List.of(String.format("Bearer %s", this.apiKey)));

    return this.transport.request(
        "certification_request",
        HexConverter.encode(request.toCbor()),
        CertificationResponse.class,
        headers
    );
  }

  /**
   * Get inclusion proof for state id.
   *
   * @param stateId state id
   * @return inclusion / non inclusion proof
   */
  @Override
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
    InclusionProofRequest request = new InclusionProofRequest(
        Objects.requireNonNull(stateId, "stateId cannot be null"));

    return this.transport
        .request("get_inclusion_proof.v2", request, String.class)
        .thenApply(response -> InclusionProofResponse.fromCbor(HexConverter.decode(response)));
  }

  /**
   * Get block height.
   *
   * @return block height
   */
  @Override
  public CompletableFuture<Long> getBlockHeight() {
    return this.transport.request("get_block_height", Map.of(), BlockHeightResponse.class)
        .thenApply(BlockHeightResponse::getBlockNumber);
  }
}
