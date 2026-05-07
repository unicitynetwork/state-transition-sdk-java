package org.unicitylabs.sdk.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.common.CommonTestFlow;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for token operations using CommonTestFlow. Matches TypeScript SDK's test
 * structure.
 */

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "AGGREGATOR_URL", matches = ".+")
public class TokenE2ETest extends CommonTestFlow {

  private JsonRpcAggregatorClient aggregatorClient;

  @BeforeEach
  void setUp() throws IOException {
    String aggregatorUrl = System.getenv("AGGREGATOR_URL");
    assertNotNull(aggregatorUrl, "AGGREGATOR_URL environment variable must be set");

    this.aggregatorClient = new JsonRpcAggregatorClient(aggregatorUrl);
    this.client = new StateTransitionClient(this.aggregatorClient);
    try (InputStream stream = getClass().getResourceAsStream("/trust-base.json")) {
      assertNotNull(stream, "trust-base.json not found");
      this.trustBase = RootTrustBase.fromJson(new String(stream.readAllBytes()));
      this.predicateVerifier = PredicateVerifierService.create();
      this.mintJustificationVerifier = new MintJustificationVerifierService();
    }
  }

  @Test
  void testGetBlockHeight() throws Exception {
    Long blockHeight = aggregatorClient.getBlockHeight().get();
    assertNotNull(blockHeight);
    assertTrue(blockHeight > 0);
  }
}