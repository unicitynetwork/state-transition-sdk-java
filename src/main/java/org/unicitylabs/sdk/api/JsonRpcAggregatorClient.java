package org.unicitylabs.sdk.api;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcHttpTransport;

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
    Objects.requireNonNull(url, "url cannot be null");

    this.transport = new JsonRpcHttpTransport(url);
    this.apiKey = apiKey;
  }

  public CompletableFuture<CertificationResponse> submitCertificationRequest(
      CertificationData certificationData
  ) {
    Objects.requireNonNull(certificationData, "certificationData cannot be null");

    CertificationRequest request = CertificationRequest.create(certificationData);

    Map<String, List<String>> headers = this.apiKey == null
        ? Map.of()
        : Map.of(AUTHORIZATION, List.of(String.format("Bearer %s", this.apiKey)));

    return this.transport.request(
        "certification_request",
        request,
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
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
    Objects.requireNonNull(stateId, "stateId cannot be null");

    InclusionProofRequest request = new InclusionProofRequest(stateId);

    return this.transport.request("get_inclusion_proof.v2", request, InclusionProofResponse.class);
  }

  /**
   * Get block height.
   *
   * @return block height
   */
  public CompletableFuture<Long> getBlockHeight() {
    return this.transport.request("get_block_height", Map.of(), BlockHeightResponse.class)
        .thenApply(BlockHeightResponse::getBlockNumber);
  }
}