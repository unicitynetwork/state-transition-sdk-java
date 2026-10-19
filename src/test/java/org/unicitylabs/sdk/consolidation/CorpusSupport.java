package org.unicitylabs.sdk.consolidation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.unicitylabs.sdk.api.BatchAggregatorClient;
import org.unicitylabs.sdk.api.BatchProofException;
import org.unicitylabs.sdk.api.BatchRequestOptions;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.InclusionProofBatch;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Loader of the vendored uc-consolidation v1 corpus and a fake shard client that serves its JSON-RPC
 * responses. Test-only: the mapping of JSON-RPC errors to {@link BatchProofException} follows the
 * reference transport, the real one is the HTTP adapter's job.
 */
final class CorpusSupport {

  static final Path ROOT = Paths.get("src/test/resources/shared/uc-consolidation/v1");
  static final ObjectMapper JSON = new ObjectMapper();

  private CorpusSupport() {
  }

  /** One request a fake client saw. */
  static final class Call {
    final String shard;
    final int stateCount;
    final boolean exactRoot;
    final Long waitMillis;

    Call(String shard, int stateCount, boolean exactRoot, Long waitMillis) {
      this.shard = shard;
      this.stateCount = stateCount;
      this.exactRoot = exactRoot;
      this.waitMillis = waitMillis;
    }
  }

  /** A case with the files of its base for everything it does not change. */
  static final class Case {
    final String name;
    final JsonNode expected;
    private final Path own;
    private final Path base;

    Case(String name) throws IOException {
      this.name = name;
      this.own = ROOT.resolve(name);
      this.expected = JSON.readTree(own.resolve("expected.json").toFile());
      this.base = expected.has("base") ? ROOT.resolve(expected.get("base").asText()) : null;
    }

    byte[] bytes(String file) throws IOException {
      Path path = Files.exists(own.resolve(file)) ? own.resolve(file) : base.resolve(file);
      return Files.readAllBytes(path);
    }

    JsonNode json(String file) throws IOException {
      return JSON.readTree(bytes(file));
    }

    boolean hasOwn(String file) {
      return Files.exists(own.resolve(file));
    }
  }

  static List<String> negativeCaseNames() throws IOException {
    try (Stream<Path> dirs = Files.list(ROOT.resolve("negative"))) {
      return dirs.map(p -> "negative/" + p.getFileName()).sorted().collect(Collectors.toList());
    }
  }

  static String sha256Hex(byte[] bytes) {
    try {
      return HexConverter.encode(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static ShardId shard(String bits) {
    return ShardId.fromBitString(bits);
  }

  /** Go trust base CBOR: tag 39000 [version, network, epoch, epochStart, [[id, key, stake]...], quorum, ...]. */
  static RootTrustBase trustBase(byte[] cbor) {
    List<byte[]> data = CborDeserializer.decodeArray(CborDeserializer.decodeTag(cbor).getData());
    ObjectNode json = JSON.createObjectNode();
    json.put("version", 1);
    json.put("networkId", CborDeserializer.decodeUnsignedInteger(data.get(1)).asLong());
    json.put("epoch", Long.toUnsignedString(
        CborDeserializer.decodeUnsignedInteger(data.get(2)).asLong()));
    json.put("epochStartRound", Long.toUnsignedString(
        CborDeserializer.decodeUnsignedInteger(data.get(3)).asLong()));
    ArrayNode nodes = json.putArray("rootNodes");
    for (byte[] node : CborDeserializer.decodeArray(data.get(4))) {
      List<byte[]> fields = CborDeserializer.decodeArray(node, 3);
      ObjectNode entry = nodes.addObject();
      entry.put("nodeId", CborDeserializer.decodeTextString(fields.get(0)));
      entry.put("sigKey", "0x" + HexConverter.encode(
          CborDeserializer.decodeByteString(fields.get(1))));
      entry.put("stake", Long.toUnsignedString(
          CborDeserializer.decodeUnsignedInteger(fields.get(2)).asLong()));
    }
    json.put("quorumThreshold", Long.toUnsignedString(
        CborDeserializer.decodeUnsignedInteger(data.get(5)).asLong()));
    json.put("stateHash", "");
    json.putNull("changeRecordHash");
    json.putNull("previousEntryHash");
    json.putObject("signatures");
    try {
      return RootTrustBase.fromJson(JSON.writeValueAsString(json));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Certification data inside a Go certification request (tag 39030). */
  static CertificationData certificationDataOf(String requestHex) {
    List<byte[]> request = CborDeserializer.decodeArray(
        CborDeserializer.decodeTag(HexConverter.decode(requestHex)).getData(), 4);
    return CertificationData.fromCbor(request.get(2));
  }

  static List<ConsolidationItem> evidenceOf(JsonNode requests) {
    List<ConsolidationItem> items = new ArrayList<>();
    for (JsonNode evidence : requests.get("evidence")) {
      InclusionProofResponse response =
          InclusionProofResponse.fromCbor(HexConverter.decode(evidence.get("proof").asText()));
      if (response.getInclusionProof() == null) {
        throw new IllegalArgumentException("Evidence proof is not certified.");
      }
      items.add(new ConsolidationItem(certificationDataOf(evidence.get("request").asText()),
          response.getInclusionProof()));
    }
    return items;
  }

  /** The reference transport's classification of a JSON-RPC error of get_inclusion_proofs.v1. */
  static BatchProofException rpcFailure(JsonNode error) {
    int code = error.get("code").asInt();
    String message = error.path("message").asText();
    JsonNode data = error.path("data");
    String detail = data.has("detail") ? data.get("detail").asText() : null;
    boolean retryable = data.path("retryable").asBoolean(false);
    switch (code) {
      case -32008:
        if (BatchProofException.isStateDetail(detail)) {
          List<Integer> indexes = null;
          JsonNode own = data.path("indexes").path(detail.replaceFirst("^state_", ""));
          if (own.isArray()) {
            indexes = new ArrayList<>();
            for (JsonNode index : own) {
              indexes.add(index.asInt());
            }
          }
          return new BatchProofException(BatchProofException.Kind.UNAVAILABLE, message, code,
              detail, indexes, "state_pending".equals(detail), 1, null, null);
        }
        return "unsupported_configuration".equals(detail)
            ? new BatchProofException(BatchProofException.Kind.UNSUPPORTED, message, code, detail,
                null, false, 1, null, null)
            : new BatchProofException(BatchProofException.Kind.UNAVAILABLE, message, code, detail,
                null, retryable, 1, null, null);
      case -32006:
        return new BatchProofException(BatchProofException.Kind.UNAVAILABLE, message, code,
            detail != null ? "busy:" + detail : "busy", null, true, 1, null, null);
      case -32601:
        return new BatchProofException(BatchProofException.Kind.UNSUPPORTED, message, code,
            "method_not_found", null, false, 1, null, null);
      case -32602:
      case -32600:
        return new BatchProofException(BatchProofException.Kind.REJECTED, message, code, detail,
            null, false, 1, null, null);
      case -32007:
        return new BatchProofException(BatchProofException.Kind.LIMIT_EXCEEDED, message, code,
            detail, null, false, 1, null, null);
      default:
        return new BatchProofException(BatchProofException.Kind.SERVER, message, code, detail,
            null, retryable, 1, null, null);
    }
  }

  /** A shard aggregator that answers with one canned JSON-RPC response and records its calls. */
  static final class FakeShardClient implements BatchAggregatorClient {
    final String shard;
    final JsonNode response;
    final List<Call> calls;

    FakeShardClient(String shard, JsonNode response, List<Call> calls) {
      this.shard = shard;
      this.response = response;
      this.calls = calls;
    }

    @Override
    public CompletableFuture<InclusionProofBatch> getInclusionProofs(List<StateId> stateIds,
        BatchRequestOptions options) {
      calls.add(new Call(shard, stateIds.size(), options.getStateRoot() != null,
          options.getWaitMillis()));
      CompletableFuture<InclusionProofBatch> future = new CompletableFuture<>();
      if (response.has("error")) {
        future.completeExceptionally(rpcFailure(response.get("error")));
        return future;
      }
      try {
        JsonNode result = response.get("result");
        JsonNode proofs = result.get("proofs");
        if (proofs == null || !proofs.isArray() || proofs.size() != stateIds.size()) {
          throw new BatchProofException(BatchProofException.Kind.INVALID_RESPONSE,
              "Expected " + stateIds.size() + " proofs.", null, false);
        }
        if (result.has("partitionView") && options.getStateRoot() != null) {
          throw new BatchProofException(BatchProofException.Kind.INVALID_RESPONSE,
              "Unexpected partition view.", null, false);
        }
        List<InclusionProofResponse> decoded = new ArrayList<>();
        for (JsonNode proof : proofs) {
          decoded.add(InclusionProofResponse.fromCbor(HexConverter.decode(proof.asText())));
        }
        byte[] view = result.has("partitionView")
            ? HexConverter.decode(result.get("partitionView").asText()) : null;
        future.complete(new InclusionProofBatch(decoded, view, 1, null));
      } catch (BatchProofException e) {
        future.completeExceptionally(e);
      } catch (RuntimeException e) {
        future.completeExceptionally(new BatchProofException(
            BatchProofException.Kind.INVALID_RESPONSE, "Malformed batch result: " + e.getMessage(),
            null, null, null, false, 1, null, e));
      }
      return future;
    }

    @Override
    public CompletableFuture<CertificationResponse> submitCertificationRequest(
        CertificationData certificationData) {
      throw new UnsupportedOperationException("A consolidation never submits.");
    }

    @Override
    public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
      throw new UnsupportedOperationException("A batch is never emulated by single requests.");
    }

    @Override
    public CompletableFuture<Long> getLatestBlockNumber() {
      throw new UnsupportedOperationException("A consolidation never asks for the block number.");
    }
  }

  static List<Call> newCalls() {
    return Collections.synchronizedList(new CopyOnWriteArrayList<>());
  }

  static String utf8(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
