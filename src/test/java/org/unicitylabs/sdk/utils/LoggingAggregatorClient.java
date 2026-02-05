package org.unicitylabs.sdk.utils;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.Authenticator;
import org.unicitylabs.sdk.api.InclusionProofRequest;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.RequestId;
import org.unicitylabs.sdk.api.SubmitCommitmentRequest;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.hash.DataHash;
import org.unicitylabs.sdk.jsonrpc.JsonRpcRequest;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;

/**
 * A decorating wrapper around {@link AggregatorClient} that adds configurable
 * debug logging with full JSON output. Useful for test debugging without
 * modifying SDK source files.
 *
 * <p>Control output via system properties (all default to {@code false}):
 * <ul>
 *   <li>{@code sdk.log.commitments} — log submitCommitment request/response JSON</li>
 *   <li>{@code sdk.log.proofs} — log getInclusionProof request/response JSON</li>
 *   <li>{@code sdk.log.blockHeight} — log getBlockHeight response</li>
 *   <li>{@code sdk.log.envelope} — log full JSON-RPC envelope (jsonrpc/id/method/params)</li>
 *   <li>{@code sdk.log.all} — shortcut to enable all of the above</li>
 * </ul>
 *
 * <p>Note: when {@code sdk.log.envelope} is enabled the logged JSON-RPC {@code id}
 * is generated locally and will differ from the actual UUID sent by the transport.
 * Use the {@code requestId} inside params to match against aggregator logs.
 *
 * <p>Example: {@code -Dsdk.log.all=true} or {@code -Dsdk.log.commitments=true -Dsdk.log.envelope=true}
 */
public class LoggingAggregatorClient implements AggregatorClient {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final AggregatorClient delegate;

    public LoggingAggregatorClient(AggregatorClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<SubmitCommitmentResponse> submitCommitment(
            RequestId requestId,
            DataHash transactionHash,
            Authenticator authenticator
    ) {
        if (isEnabled("commitments")) {
            SubmitCommitmentRequest params = new SubmitCommitmentRequest(
                    requestId, transactionHash, authenticator, true);
            if (isEnabled("envelope")) {
                log("submitCommitment REQUEST: %s",
                        toJson(new JsonRpcRequest("submit_commitment", params)));
            } else {
                log("submitCommitment REQUEST: %s", params.toJson());
            }
        }

        return delegate.submitCommitment(requestId, transactionHash, authenticator)
                .whenComplete((response, error) -> {
                    if (!isEnabled("commitments")) return;
                    if (error != null) {
                        log("submitCommitment ERROR: %s", error.getMessage());
                    } else {
                        log("submitCommitment RESPONSE: %s", toJson(response));
                    }
                });
    }

    @Override
    public CompletableFuture<InclusionProofResponse> getInclusionProof(RequestId requestId) {
        if (isEnabled("proofs")) {
            InclusionProofRequest params = new InclusionProofRequest(requestId);
            if (isEnabled("envelope")) {
                log("getInclusionProof REQUEST: %s",
                        toJson(new JsonRpcRequest("get_inclusion_proof", params)));
            } else {
                log("getInclusionProof REQUEST: %s", params.toJson());
            }
        }

        return delegate.getInclusionProof(requestId)
                .whenComplete((response, error) -> {
                    if (!isEnabled("proofs")) return;
                    if (error != null) {
                        log("getInclusionProof ERROR: %s", error.getMessage());
                    } else {
                        log("getInclusionProof RESPONSE: %s", toJson(response));
                    }
                });
    }

    @Override
    public CompletableFuture<Long> getBlockHeight() {
        if (isEnabled("blockHeight")) {
            if (isEnabled("envelope")) {
                log("getBlockHeight REQUEST: %s",
                        toJson(new JsonRpcRequest("get_block_height", java.util.Map.of())));
            } else {
                log("getBlockHeight REQUEST");
            }
        }

        return delegate.getBlockHeight()
                .whenComplete((height, error) -> {
                    if (!isEnabled("blockHeight")) return;
                    if (error != null) {
                        log("getBlockHeight ERROR: %s", error.getMessage());
                    } else {
                        log("getBlockHeight RESPONSE: %d", height);
                    }
                });
    }

    public AggregatorClient getDelegate() {
        return delegate;
    }

    private static boolean isEnabled(String feature) {
        return Boolean.getBoolean("sdk.log.all")
                || Boolean.getBoolean("sdk.log." + feature);
    }

    private static void log(String format, Object... args) {
        System.out.printf("[%s] [SDK] " + format + "%n",
                prependTimestamp(args));
    }

    private static Object[] prependTimestamp(Object[] args) {
        Object[] result = new Object[args.length + 1];
        result[0] = LocalTime.now().format(TIME_FMT);
        System.arraycopy(args, 0, result, 1, args.length);
        return result;
    }

    private static String toJson(Object obj) {
        try {
            return UnicityObjectMapper.JSON.writeValueAsString(obj);
        } catch (Exception e) {
            return "<serialization error: " + e.getMessage() + ">";
        }
    }
}
