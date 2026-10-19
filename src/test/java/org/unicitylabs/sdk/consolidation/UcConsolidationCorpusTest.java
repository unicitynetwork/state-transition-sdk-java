package org.unicitylabs.sdk.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.bft.PartitionView;
import org.unicitylabs.sdk.api.bft.PartitionViewException;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.ShardConfigurationPin;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.api.bft.verification.OperationUnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;
import org.unicitylabs.sdk.consolidation.CorpusSupport.Call;
import org.unicitylabs.sdk.consolidation.CorpusSupport.Case;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * The shared uc-consolidation v1 corpus, through the real consolidator with fake shard clients that
 * serve the corpus' JSON-RPC responses. Outcomes bind to the reference names, never to message text.
 */
class UcConsolidationCorpusTest {

  private static final List<String> POSITIVE = List.of("shards-1", "shards-4", "shards-8",
      "shards-4-timeout-unchanged", "large-values", "witness-alt", "nil-vs-empty");

  /** What one run observed: a result, or the typed failure it ended with. */
  private static final class Observation {
    ConsolidationAttempt attempt;
    ConsolidationException failure;
    List<Call> calls;

    String outcome() {
      if (failure != null) {
        switch (failure.getKind()) {
          case REJECTED:
            return "invalid_request";
          case SERVER:
            return "infrastructure";
          case INVALID_RESPONSE:
            return "invalid_input";
          default:
            return failure.getOutcome() != null ? failure.getOutcome().name().toLowerCase()
                : failure.getKind().name().toLowerCase();
        }
      }
      if (attempt.getStatus() == ConsolidationAttempt.Status.CONSOLIDATED) {
        return "ok";
      }
      Unavailability unavailable = attempt.getUnavailability();
      switch (unavailable.getReason()) {
        case BUSY:
          return "busy";
        case LIMIT_EXCEEDED:
          return "limit_exceeded";
        case UNSUPPORTED_CONFIGURATION:
          return "unsupported";
        case UNSUPPORTED_CAPABILITY:
          return "unsupported_method";
        case PENDING_PUBLICATION:
          return "master_round_below_required".equals(unavailable.getDetail()) ? "stale"
              : "unavailable";
        default:
          return "unavailable";
      }
    }
  }

  static List<String> negativeCases() throws IOException {
    return CorpusSupport.negativeCaseNames();
  }

  private static ShardRouting routing(Case c, Map<String, JsonNode> responses, List<Call> calls)
      throws IOException {
    JsonNode context = c.json("context.json");
    // the context's partition is an equality assertion against the fixed aggregation partition
    if (context.get("partitionId").asLong() != 7) {
      throw ConsolidationException.of(ConsolidationException.Kind.INPUT,
          ConsolidationOutcome.INVALID_INPUT, "The context asserts another partition.", null, null);
    }
    List<ShardId> scheme = new ArrayList<>();
    boolean single = context.get("shards").size() == 1 && context.get("shards").get(0).asText().isEmpty();
    if (!single) {
      for (JsonNode bits : context.get("shards")) {
        scheme.add(CorpusSupport.shard(bits.asText()));
      }
    }
    List<ShardEndpoint> endpoints = new ArrayList<>();
    for (JsonNode bits : context.get("participants")) {
      endpoints.add(new ShardEndpoint(CorpusSupport.shard(bits.asText()),
          new CorpusSupport.FakeShardClient(bits.asText(), responses.get(bits.asText()), calls)));
    }
    List<ShardConfigurationPin> pins = new ArrayList<>();
    for (JsonNode config : context.get("shardConfigs")) {
      pins.add(new ShardConfigurationPin(CorpusSupport.shard(config.get("shard").asText()),
          HexConverter.decode(config.get("hash").asText())));
    }
    return new ShardRouting(endpoints, scheme, pins);
  }

  private static Map<String, JsonNode> responses(Case c) throws IOException {
    JsonNode all = c.json("responses.json");
    Map<String, JsonNode> byShard = new HashMap<>();
    byShard.put(c.json("context.json").get("master").asText(), all.get("master"));
    if (all.has("peers")) {
      for (JsonNode peer : all.get("peers")) {
        byShard.put(peer.get("shard").asText(), peer.get("response"));
      }
    }
    return byShard;
  }

  private static Observation execute(Case c) throws IOException {
    Observation observed = new Observation();
    observed.calls = CorpusSupport.newCalls();
    try {
      RootTrustBase trustBase = CorpusSupport.trustBase(c.bytes("trust-base.cbor"));
      ShardRouting routing = routing(c, responses(c), observed.calls);
      OperationUnicityCertificateVerifier verifier = (OperationUnicityCertificateVerifier)
          new UnicityCertificateVerifier().beginOperation(VerificationPolicy.LEGACY_QUORUM,
              PartitionViewPolicy.none());
      ProofConsolidator consolidator = new ProofConsolidator(routing, trustBase, verifier);
      List<ConsolidationItem> items;
      try {
        items = CorpusSupport.evidenceOf(c.json("requests.json"));
      } catch (RuntimeException e) {
        // evidence that does not even decode never reaches the consolidator
        observed.failure = ConsolidationException.of(ConsolidationException.Kind.INPUT,
            ConsolidationOutcome.INVALID_INPUT, e.getMessage(), null, e);
        return observed;
      }
      observed.attempt = consolidator.consolidate(items, ConsolidationOptions.defaults()
          .withMaster(CorpusSupport.shard(c.json("context.json").get("master").asText()))
          .withTimeout(Duration.ofSeconds(20))
          .withExecutor(Runnable::run)).join();
    } catch (CompletionException e) {
      observed.failure = (ConsolidationException) e.getCause();
    } catch (ConsolidationException e) {
      observed.failure = e;
    }
    return observed;
  }

  @Test
  void theNegativeDirectoryHoldsEveryCaseTheManifestListsAndTheSevenPositivesAreThere()
      throws IOException {
    JsonNode manifest = CorpusSupport.JSON.readTree(CorpusSupport.ROOT.resolve("manifest.json")
        .toFile());
    Set<String> listed = new HashSet<>();
    for (JsonNode entry : manifest.get("cases")) {
      listed.add(entry.get("name").asText());
    }
    Set<String> present = new HashSet<>(POSITIVE);
    present.addAll(negativeCases());
    assertEquals(listed, present);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("positiveCases")
  void positiveCaseYieldsTheReferenceBundleAndCounters(String name) throws IOException {
    Case c = new Case(name);
    Observation run = execute(c);
    assertEquals(null, run.failure == null ? null : run.failure.toString() + run.failure.getCause());
    assertEquals(ConsolidationAttempt.Status.CONSOLIDATED, run.attempt.getStatus());

    ConsolidatedProofs proofs = run.attempt.getProofs();
    JsonNode expected = c.expected;
    assertEquals(expected.get("rMin").asText(), proofs.getRequiredRoundDecimal());
    assertEquals(expected.get("rootRound").asText(), proofs.getMasterRoundDecimal());
    assertEquals(expected.get("master").asText(), proofs.getMasterShard().toString());
    JsonNode counters = expected.get("counters");
    assertEquals(counters.get("bodyChecks").asLong(), proofs.getCounters().getBodyChecks());
    assertEquals(counters.get("leafChecks").asLong(), proofs.getCounters().getLeafChecks());
    assertEquals(counters.get("evidenceQuorumChecks").asLong(),
        proofs.getCounters().getEvidenceQuorumChecks());
    // exactly the quorum check of the master seal: none lost, none repeated
    assertEquals(counters.get("quorumChecks").asLong(), proofs.getCounters().getQuorumChecks());
    int shards = expected.get("shards").size();
    assertEquals(shards, proofs.getShards());
    assertEquals(shards > 1 ? 2 : 1, proofs.getWaves());
    assertEquals(shards, proofs.getRequests());
    assertEquals(shards, run.calls.size());

    // the replacement proofs are exactly the reference bundle, in input order per shard
    Map<String, JsonNode> byShard = new HashMap<>();
    for (JsonNode shard : expected.get("shards")) {
      byShard.put(shard.get("shard").asText(), shard);
    }
    Map<String, Integer> seen = new HashMap<>();
    List<ConsolidationItem> evidence = CorpusSupport.evidenceOf(c.json("requests.json"));
    Set<String> seals = new HashSet<>();
    for (int i = 0; i < proofs.getResponses().size(); i++) {
      InclusionProofResponse response = proofs.getResponses().get(i);
      String shard = response.getInclusionProof().getUnicityCertificate().getShardTreeCertificate()
          .getShard().toString();
      JsonNode reference = byShard.get(shard);
      assertNotNull(reference, shard);
      assertEquals(reference.get("ucSha256").asText(), CorpusSupport.sha256Hex(
          response.getInclusionProof().getUnicityCertificate().toCbor()));
      int position = seen.merge(shard, 1, Integer::sum) - 1;
      assertEquals(reference.get("proofSha256").get(position).asText(),
          CorpusSupport.sha256Hex(response.toCbor()));
      // original data and reference time are preserved
      assertEquals(HexConverter.encode(evidence.get(i).getCertificationData().toCbor()),
          HexConverter.encode(response.getInclusionProof().getCertificationData().toCbor()));
      assertEquals(evidence.get(i).getProof().getReferenceTime(),
          response.getInclusionProof().getReferenceTime());
      seals.add(HexConverter.encode(
          response.getInclusionProof().getUnicityCertificate().getUnicitySeal().toCbor()));
    }
    // one identical master seal under every output certificate
    assertEquals(1, seals.size());

    // the master asks for the latest view, no peer ever waits and every peer asks for its exact root
    Call master = run.calls.get(0);
    assertFalse(master.exactRoot);
    for (Call call : run.calls.subList(1, run.calls.size())) {
      assertTrue(call.exactRoot);
      assertEquals(null, call.waitMillis);
    }
  }

  static List<String> positiveCases() {
    return POSITIVE;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("negativeCases")
  void negativeCaseIsRefusedWithTheReferenceOutcome(String name) throws IOException {
    Case c = new Case(name);
    String stage = c.expected.get("stage").asText();
    String outcome = c.expected.get("outcome").asText();
    // cases whose input does not exist in this SDK: requests are derived from the evidence, there
    // is no way to supply them separately
    Map<String, String> notApplicable = Map.of(
        "negative/request-differs-from-evidence", "requests are derived from the evidence",
        "negative/request-wrong-state-id", "requests are derived from the evidence",
        "negative/evidence-missing-for-request",
        "every request is derived from its own evidence, none can lack one",
        "negative/rmin-unknown", "R_min is derived from the evidence, none cannot be asked for");
    Assumptions.assumeFalse(notApplicable.containsKey(name), notApplicable.get(name));

    if ("policy".equals(stage)) {
      String observed = "ok";
      try {
        routing(c, responses(c), CorpusSupport.newCalls());
        RootTrustBase trustBase = CorpusSupport.trustBase(c.bytes("trust-base.cbor"));
        if (c.json("context.json").get("networkId").asInt() != trustBase.getNetworkId().getId()) {
          observed = "incompatible_lineage";
        }
      } catch (ConsolidationException e) {
        observed = e.getOutcome().name().toLowerCase();
      }
      assertEquals(outcome, observed);
      return;
    }

    if ("view_codec".equals(stage)) {
      assertThrows(PartitionViewException.class,
          () -> PartitionView.fromCbor(c.bytes("partition-view.cbor")));
      // and the whole pipeline refuses the response it came with
      String pipeline = c.expected.has("pipelineOutcome")
          ? c.expected.get("pipelineOutcome").asText() : outcome;
      assertEquals(pipeline, execute(c).outcome());
      return;
    }

    Observation run = execute(c);
    assertEquals(outcome, run.outcome(), () -> String.valueOf(run.failure));
    if ("negative/rmin-master-stale".equals(name)) {
      // evidence newer than the master seal: pending publication after the one master request
      assertEquals(ProofUnavailableReason.PENDING_PUBLICATION,
          run.attempt.getUnavailability().getReason());
      assertEquals("master_round_below_required", run.attempt.getUnavailability().getDetail());
      assertTrue(run.attempt.getUnavailability().isRetryable());
      assertEquals(1, run.calls.size());
    }

    // the aggregator's state status details are reported under their own names
    JsonNode rpc = c.expected.path("rpc");
    String detail = rpc.path("detail").asText("");
    if ("rpc".equals(stage) && Set.of("state_pending", "state_expired", "state_unknown",
        "state_not_in_root").contains(detail)) {
      Unavailability unavailable = run.attempt.getUnavailability();
      assertEquals(detail, unavailable.getReason().name().toLowerCase());
      assertEquals(detail, unavailable.getDetail());
      assertEquals(rpc.get("retryable").asBoolean(), unavailable.isRetryable());
      JsonNode failing = "master".equals(rpc.get("role").asText())
          ? c.json("responses.json").get("master")
          : peerResponse(c, rpc.get("shard").asText());
      JsonNode own = failing.get("error").get("data").get("indexes")
          .get(detail.replaceFirst("^state_", ""));
      // positions of the failing shard's batch, as positions in the evidence (the token's outer
      // history)
      List<String> shardOfEvidence = CorpusSupport.evidenceOf(c.json("requests.json")).stream()
          .map(e -> e.getProof().getUnicityCertificate().getShardTreeCertificate().getShard()
              .toString()).collect(Collectors.toList());
      List<Integer> group = new ArrayList<>();
      for (int i = 0; i < shardOfEvidence.size(); i++) {
        if (shardOfEvidence.get(i).equals(rpc.get("shard").asText())) {
          group.add(i);
        }
      }
      List<Integer> expectedIndexes = new ArrayList<>();
      for (JsonNode index : own) {
        expectedIndexes.add(group.get(index.asInt()));
      }
      assertEquals(expectedIndexes, unavailable.getProofIndexes());
    }
  }

  private static JsonNode peerResponse(Case c, String shard) throws IOException {
    for (JsonNode peer : c.json("responses.json").get("peers")) {
      if (peer.get("shard").asText().equals(shard)) {
        return peer.get("response");
      }
    }
    throw new IllegalArgumentException(shard);
  }

  @Test
  void aStateStatusIsClassifiedUnderAnyDefaultLocale() throws IOException {
    java.util.Locale saved = java.util.Locale.getDefault();
    try {
      java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
      Observation run = execute(new Case("negative/rpc-master-state-pending"));
      assertEquals(null, run.failure);
      assertEquals(ProofUnavailableReason.STATE_PENDING, run.attempt.getUnavailability().getReason());
    } finally {
      java.util.Locale.setDefault(saved);
    }
  }
}
