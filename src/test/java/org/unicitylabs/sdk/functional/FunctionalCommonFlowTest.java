package org.unicitylabs.sdk.functional;

import org.junit.jupiter.api.BeforeEach;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.common.CommonTestFlow;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;

public class FunctionalCommonFlowTest extends CommonTestFlow {

  @BeforeEach
  void setUp() {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    this.client = new StateTransitionClient(aggregatorClient);
    this.trustBase = aggregatorClient.getTrustBase();
    this.predicateVerifier = PredicateVerifierService.create(this.trustBase);
  }
}