package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.e2e.support.ShardAwareAggregatorClient;
import org.unicitylabs.sdk.e2e.support.StrictTestAggregatorClient;
import org.unicitylabs.sdk.functional.payment.TestPaymentData;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;

public class AggregatorSteps {

  private final TestContext context;

  public AggregatorSteps(TestContext context) {
    this.context = context;
  }

  @Given("a mock aggregator is running")
  public void aMockAggregatorIsRunning() {
    // If AGGREGATOR_URL is set, wire up a real JSON-RPC client and load the
    // trust base from TRUST_BASE_PATH. Otherwise fall back to the hermetic
    // StrictTestAggregatorClient. This mirrors the TS suite's runtime env
    // (AGGREGATOR_URL + TRUST_BASE_PATH).
    String url = System.getenv("AGGREGATOR_URL");
    if (url != null && !url.isEmpty()) {
      try {
        String apiKey = System.getenv("AGGREGATOR_API_KEY");
        AggregatorClient real = (apiKey != null && !apiKey.isEmpty())
            ? new JsonRpcAggregatorClient(url, apiKey)
            : new JsonRpcAggregatorClient(url);
        RootTrustBase trustBase = loadTrustBase();
        context.setAggregatorClient(real);
        context.setClient(new StateTransitionClient(real));
        context.setTrustBase(trustBase);
        wireVerifiers(trustBase);
        return;
      } catch (Exception e) {
        throw new RuntimeException(
            "Failed to wire real aggregator at " + url
                + " (check TRUST_BASE_PATH): " + e.getMessage(), e);
      }
    }

    StrictTestAggregatorClient aggregator = StrictTestAggregatorClient.create();
    context.setAggregatorClient(aggregator);
    context.setClient(new StateTransitionClient(aggregator));
    context.setTrustBase(aggregator.getTrustBase());
    wireVerifiers(aggregator.getTrustBase());
  }

  /**
   * Builds {@link PredicateVerifierService} (no-arg post-issue-50) and a
   * {@link MintJustificationVerifierService} pre-registered with
   * {@link SplitMintJustificationVerifier} so split-child mints verify.
   */
  private void wireVerifiers(RootTrustBase trustBase) {
    PredicateVerifierService predVerifier = PredicateVerifierService.create();
    context.setPredicateVerifier(predVerifier);

    MintJustificationVerifierService mjv = new MintJustificationVerifierService();
    mjv.register(new SplitMintJustificationVerifier(
        trustBase, predVerifier, TestPaymentData::decode));
    context.setMintJustificationVerifier(mjv);
  }

  private static RootTrustBase loadTrustBase() throws java.io.IOException {
    String path = System.getenv("TRUST_BASE_PATH");
    if (path == null || path.isEmpty()) {
      // Default: classpath resource shipped under test/resources/trust-base.json.
      try (java.io.InputStream in =
          AggregatorSteps.class.getClassLoader().getResourceAsStream("trust-base.json")) {
        if (in == null) {
          throw new java.io.FileNotFoundException(
              "TRUST_BASE_PATH not set and trust-base.json not on classpath");
        }
        return UnicityObjectMapper.JSON.readValue(in.readAllBytes(), RootTrustBase.class);
      }
    }
    byte[] bytes = Files.readAllBytes(Paths.get(path));
    return UnicityObjectMapper.JSON.readValue(bytes, RootTrustBase.class);
  }

  @Given("a mock aggregator client is set up")
  public void aMockAggregatorClientIsSetUp() {
    aMockAggregatorIsRunning();
  }

  @When("I request the current block height")
  public void iRequestTheCurrentBlockHeight() throws Exception {
    // Skip against real aggregators: the bft-shard subscription proxy
    // requires every JSON-RPC call to carry stateId or shardId, but
    // getBlockHeight() is a state-free probe with no routing info.
    String aggregatorUrl = System.getenv("AGGREGATOR_URL");
    if (aggregatorUrl != null && !aggregatorUrl.isEmpty()) {
      throw new org.opentest4j.TestAbortedException(
          "Block height test is hermetic-only — real proxy rejects getBlockHeight() without stateId/shardId");
    }
    Long height = context.getAggregatorClient().getBlockHeight().get();
    context.setBlockHeight(height);
  }

  @Then("a block height is returned")
  public void aBlockHeightIsReturned() {
    assertNotNull(context.getBlockHeight(), "block height should be non-null");
    assertTrue(context.getBlockHeight() >= 0L, "block height should be non-negative");
  }
}
