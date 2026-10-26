package org.unicitylabs.sdk.consolidation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.unicitylabs.sdk.api.BatchAggregatorClient;
import org.unicitylabs.sdk.api.BatchProofException;
import org.unicitylabs.sdk.api.BatchRequestOptions;
import org.unicitylabs.sdk.api.InclusionProofBatch;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.api.bft.ShardConfigurationPin;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.api.bft.verification.OperationUnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;

/** A peer's ordinary transport timeout in wave 2 stays a retryable transport failure. */
class PeerTransportTimeoutTest {

  @Test
  void aPeerTransportTimeoutStaysRetryableAndIsNotReportedAsCancelled() throws Exception {
    CorpusSupport.Case c = new CorpusSupport.Case("shards-8");
    JsonNode context = c.json("context.json");
    JsonNode all = c.json("responses.json");
    String masterBits = context.get("master").asText();
    Map<String, JsonNode> responses = new HashMap<>();
    responses.put(masterBits, all.get("master"));
    for (JsonNode peer : all.get("peers")) {
      responses.put(peer.get("shard").asText(), peer.get("response"));
    }
    List<ShardId> scheme = new ArrayList<>();
    for (JsonNode bits : context.get("shards")) {
      scheme.add(CorpusSupport.shard(bits.asText()));
    }
    List<CorpusSupport.Call> calls = CorpusSupport.newCalls();
    List<ShardEndpoint> endpoints = new ArrayList<>();
    int[] peerSeen = {0};
    for (JsonNode bits : context.get("participants")) {
      String b = bits.asText();
      BatchAggregatorClient real = new CorpusSupport.FakeShardClient(b, responses.get(b), calls);
      BatchAggregatorClient client = b.equals(masterBits) ? real
          : (BatchAggregatorClient) java.lang.reflect.Proxy.newProxyInstance(
              BatchAggregatorClient.class.getClassLoader(), new Class<?>[] {BatchAggregatorClient.class},
              (proxy, method, args) -> {
                if (!method.getName().equals("getInclusionProofs")) {
                  return method.invoke(real, args);
                }
                int n = peerSeen[0]++;
                CompletableFuture<InclusionProofBatch> f = new CompletableFuture<>();
                if (n == 0) {
                  return f; // in flight until the wave is cancelled
                }
                f.completeExceptionally(new BatchProofException(BatchProofException.Kind.TRANSPORT,
                    "timeout", null, null, null, true, 1, null,
                    new java.net.SocketTimeoutException("read timed out")));
                return f;
              });
      endpoints.add(new ShardEndpoint(CorpusSupport.shard(b), client));
    }
    List<ShardConfigurationPin> pins = new ArrayList<>();
    for (JsonNode config : context.get("shardConfigs")) {
      pins.add(new ShardConfigurationPin(CorpusSupport.shard(config.get("shard").asText()),
          HexConverter.decode(config.get("hash").asText())));
    }
    RootTrustBase trustBase = CorpusSupport.trustBase(c.bytes("trust-base.cbor"));
    OperationUnicityCertificateVerifier verifier = (OperationUnicityCertificateVerifier)
        new UnicityCertificateVerifier().beginOperation(VerificationPolicy.LEGACY_QUORUM,
            PartitionViewPolicy.none());
    ProofConsolidator consolidator = new ProofConsolidator(new ShardRouting(endpoints, scheme, pins),
        trustBase, verifier);
    ConsolidationException failure;
    try {
      consolidator.consolidate(CorpusSupport.evidenceOf(c.json("requests.json")),
          ConsolidationOptions.defaults().withMaster(CorpusSupport.shard(masterBits))
              .withTimeout(Duration.ofSeconds(20)).withExecutor(Runnable::run)).join();
      throw new AssertionError("expected a failure");
    } catch (CompletionException e) {
      failure = (ConsolidationException) e.getCause();
    }
    assertEquals(ConsolidationException.Kind.TRANSPORT, failure.getKind(), failure.getMessage());
    assertTrue(failure.isRetryable());
    assertEquals(2, peerSeen[0], "the first peer was in flight when the second timed out");
  }
}
