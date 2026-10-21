package org.unicitylabs.sdk.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.List;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcHttpTransport;
import org.unicitylabs.sdk.internal.OperationContext;
import org.unicitylabs.sdk.internal.InclusionProofWaiter;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MethodFutureDeadlineTest {
  private static CompletableFuture<InclusionProofResponse> lookup(
      CompletableFuture<JsonNode> raw, OperationContext operation) {
    JsonRpcHttpTransport transport = new JsonRpcHttpTransport("http://localhost/") {
      @Override public CompletableFuture<JsonNode> call(String method, Object params,
          Map<String, List<String>> headers, RequestOptions options) {
        Runnable release = InclusionProofWaiter.scheduleOnSharedTimer(() ->
            raw.completeExceptionally(new TimeoutException("raw transport deadline")),
            options.getOperation().remaining().toMillis());
        raw.whenComplete((value, error) -> release.run());
        return raw;
      }
    };
    return new JsonRpcAggregatorClient(transport, null).getInclusionProof(
        WireTestSupport.stateId("ab".repeat(32)), RequestOptions.none().withOperation(operation));
  }

  private static JsonNode pausedProof(CountDownLatch entered, CountDownLatch release)
      throws Exception {
    String proof = new ObjectMapper().readTree(Files.readAllBytes(Paths.get(
        "src/test/resources/shared/uc-consolidation/v1/shards-1/responses.json")))
        .get("master").get("result").get("proofs").get(0).asText();
    return new TextNode(proof) {
      @Override public String textValue() {
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return super.textValue();
      }
    };
  }

  @Test
  void theMethodFutureTimesOutWhileDecodingIsStillRunning() throws Exception {
    CompletableFuture<JsonNode> raw = new CompletableFuture<>();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    JsonNode proof = pausedProof(entered, release);
    OperationContext operation = OperationContext.withBudget(Duration.ofMillis(300));
    CompletableFuture<InclusionProofResponse> decoded = lookup(raw, operation);
    Thread callback = new Thread(() -> raw.complete(proof));
    callback.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      ExecutionException e = assertThrows(ExecutionException.class,
          () -> decoded.get(1500, TimeUnit.MILLISECONDS),
          "The method future must fail at its budget while decoding is still running");
      assertInstanceOf(TimeoutException.class, e.getCause());
    } finally {
      release.countDown();
      callback.join(2000);
    }
  }

  @Test
  void aCancelledOperationNeverPublishesADecodedSuccess() throws Exception {
    CompletableFuture<JsonNode> raw = new CompletableFuture<>();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    JsonNode proof = pausedProof(entered, release);
    OperationContext operation = OperationContext.withBudget(Duration.ofSeconds(10));
    CompletableFuture<InclusionProofResponse> decoded = lookup(raw, operation);
    Thread callback = new Thread(() -> raw.complete(proof));
    callback.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      operation.cancel();
    } finally {
      release.countDown();
      callback.join(2000);
    }
    assertTrue(decoded.isCompletedExceptionally(), "Cancelled operation published success: " + decoded);
  }

  private static Throwable endOf(CompletableFuture<?> future) throws Exception {
    try {
      future.get(1500, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      return e.getCause();
    } catch (CancellationException e) {
      return e;
    }
    throw new AssertionError("the future did not fail");
  }

  private static JsonRpcAggregatorClient scripted(CompletableFuture<JsonNode> raw) {
    JsonRpcHttpTransport transport = new JsonRpcHttpTransport("http://localhost/") {
      @Override public CompletableFuture<JsonNode> call(String method, Object params,
          Map<String, List<String>> headers, RequestOptions options) {
        Runnable release = InclusionProofWaiter.scheduleOnSharedTimer(() ->
            raw.completeExceptionally(new TimeoutException("raw transport deadline")),
            options.getOperation().remaining().toMillis());
        raw.whenComplete((value, error) -> release.run());
        return raw;
      }
    };
    return new JsonRpcAggregatorClient(transport, null);
  }

  @Test
  void aBatchFutureEndsAtTheDeadlineAndAtCancellationWhileDecodingIsStillRunning() throws Exception {
    for (boolean cancel : new boolean[] {false, true}) {
      CompletableFuture<JsonNode> raw = new CompletableFuture<>();
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      com.fasterxml.jackson.databind.node.ObjectNode body = new ObjectMapper().createObjectNode();
      body.putArray("proofs").add(pausedProof(entered, release));
      OperationContext operation = OperationContext.withBudget(Duration.ofMillis(cancel ? 10_000 : 300));
      CompletableFuture<InclusionProofBatch> future = scripted(raw).getInclusionProofs(
          List.of(WireTestSupport.stateId("ab".repeat(32))),
          BatchRequestOptions.latest().withOperation(operation));
      Thread callback = new Thread(() -> raw.complete(body));
      callback.start();
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        if (cancel) {
          operation.cancel();
        }
        Throwable end = endOf(future);
        assertTrue(cancel ? end instanceof CancellationException : end instanceof TimeoutException, "" + end);
      } finally {
        release.countDown();
        callback.join(2000);
      }
      assertTrue(future.isCompletedExceptionally());
    }
  }

  @Test
  void aNonInclusionFutureEndsAtTheDeadlineAndAtCancellationWhileDecodingIsStillRunning() throws Exception {
    for (boolean cancel : new boolean[] {false, true}) {
      CompletableFuture<JsonNode> raw = new CompletableFuture<>();
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      JsonNode paused = pausedProof(entered, release);
      OperationContext operation = OperationContext.withBudget(Duration.ofMillis(cancel ? 10_000 : 300));
      CompletableFuture<NonInclusionProofResponse> future = scripted(raw).getNonInclusionProof(
          WireTestSupport.stateId("ab".repeat(32)), RequestOptions.none().withOperation(operation));
      long t0 = System.nanoTime();
      boolean[] completed = new boolean[1];
      Thread callback = new Thread(() -> completed[0] = raw.complete(paused));
      callback.start();
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS), "cancel=" + cancel + " completed=" + completed[0]
            + " after " + (System.nanoTime() - t0) / 1_000_000 + " ms, future=" + future + ", raw=" + raw);
        if (cancel) {
          operation.cancel();
        }
        NonInclusionProofException typed = assertInstanceOf(NonInclusionProofException.class, endOf(future));
        assertEquals(cancel ? NonInclusionProofException.Kind.CANCELLED : NonInclusionProofException.Kind.TIMEOUT,
            typed.getKind());
      } finally {
        release.countDown();
        callback.join(2000);
      }
    }
  }
  @Test
  void architectCancellationStateBlocksBatchAndNiPublicationBeforeListenerDispatch() throws Exception {
    java.util.List<String> failures = new java.util.ArrayList<>();
    for (boolean batch : new boolean[] {true, false}) {
      CompletableFuture<JsonNode> raw = new CompletableFuture<>();
      CountDownLatch decoding = new CountDownLatch(1);
      CountDownLatch releaseDecode = new CountDownLatch(1);
      CountDownLatch cancellationStarted = new CountDownLatch(1);
      CountDownLatch releaseCancellation = new CountDownLatch(1);
      OperationContext operation = OperationContext.withBudget(Duration.ofSeconds(30));
      // An earlier child of the same operation has a consumer completion callback that blocks.
      CompletableFuture<Integer> otherRaw = new CompletableFuture<>();
      CompletableFuture<Integer> otherChild = org.unicitylabs.sdk.internal.LongPoll.map(
          otherRaw, value -> value, operation);
      otherChild.whenComplete((value, error) -> {
        cancellationStarted.countDown();
        try { releaseCancellation.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      });
      JsonNode proof = pausedProof(decoding, releaseDecode);
      JsonNode body = proof;
      CompletableFuture<?> result;
      if (batch) {
        com.fasterxml.jackson.databind.node.ObjectNode b = new ObjectMapper().createObjectNode();
        b.putArray("proofs").add(proof);
        body = b;
        result = scripted(raw).getInclusionProofs(List.of(WireTestSupport.stateId("ab".repeat(32))),
            BatchRequestOptions.latest().withOperation(operation));
      } else {
        result = scripted(raw).getNonInclusionProof(WireTestSupport.stateId("ab".repeat(32)),
            RequestOptions.none().withOperation(operation));
      }
      JsonNode response = body;
      Thread callback = new Thread(() -> raw.complete(response));
      Thread cancellation = new Thread(operation::cancel);
      callback.start();
      try {
        assertTrue(decoding.await(2, TimeUnit.SECONDS));
        cancellation.start();
        assertTrue(cancellationStarted.await(2, TimeUnit.SECONDS));
        assertTrue(operation.isCancelled());
        releaseDecode.countDown();
        callback.join(2000);
        if (!result.isCompletedExceptionally()) {
          failures.add("cancelled operation published success before listener dispatch: batch=" + batch + " " + result);
        }
      } finally {
        releaseDecode.countDown();
        releaseCancellation.countDown();
        callback.join(2000);
        cancellation.join(2000);
      }
    }
    assertTrue(failures.isEmpty(), String.join("; ", failures));
  }

}
