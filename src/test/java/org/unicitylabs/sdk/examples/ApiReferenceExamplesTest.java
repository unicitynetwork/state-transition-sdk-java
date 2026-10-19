package org.unicitylabs.sdk.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;

/** Runs the documented token flow against the in-memory aggregator. */
class ApiReferenceExamplesTest {

  @Test
  void mintTransferSerializeAndVerify() throws Exception {
    TestAggregatorClient aggregator = TestAggregatorClient.create();
    StateTransitionClient client = new StateTransitionClient(aggregator);
    VerificationContext context = ApiReferenceExamples.createContext(aggregator.getTrustBase());

    byte[] key = ApiReferenceExamples.newPrivateKey();
    SigningService alice = new SigningService(key);
    SignaturePredicate aliceOwner = ApiReferenceExamples.newOwner(key);
    SigningService bob = SigningService.generate();
    SignaturePredicate bobOwner = (SignaturePredicate) ApiReferenceExamples
        .recipientFromPublicKey(bob.getPublicKey());

    Token token = ApiReferenceExamples.mint(client, context, aliceOwner, new byte[] {1, 2, 3});
    Token sent = ApiReferenceExamples.transfer(client, context, token, alice, bobOwner);
    Token received = ApiReferenceExamples.store(sent);

    ApiReferenceExamples.requireValid(received, context);
    assertTrue(ApiReferenceExamples.isMine(received, bobOwner));
    assertEquals(1, received.getTransactions().size());
    assertTrue(ApiReferenceExamples.transferCount(received).endsWith("has 1 transfers"));
    assertEquals(received.getId(),
        ApiReferenceExamples.transfer(client, context, received, bob, aliceOwner).getId());
  }

  @Test
  void trustBaseJsonRoundTrips() {
    TestAggregatorClient aggregator = TestAggregatorClient.create();
    assertEquals(aggregator.getTrustBase().getNetworkId().toString(),
        ApiReferenceExamples.trustBaseJson(aggregator.getTrustBase()));
  }

  @Test
  void splitAPaymentToken() throws Exception {
    TestAggregatorClient aggregator = TestAggregatorClient.create();
    StateTransitionClient client = new StateTransitionClient(aggregator);
    VerificationContext context = ApiReferenceExamples.splitContext(aggregator.getTrustBase());

    SigningService owner = SigningService.generate();
    SignaturePredicate predicate = SignaturePredicate.fromSigningService(owner);
    Token source = ApiReferenceExamples.mintPayment(client, context, predicate,
        ApiReferenceExamples.assets());

    SigningService other = SigningService.generate();
    List<Token> outputs = ApiReferenceExamples.split(client, context, source, owner,
        ApiReferenceExamples.splitRequests(predicate, SignaturePredicate.fromSigningService(other)));

    assertEquals(2, outputs.size());
    for (Token output : outputs) {
      ApiReferenceExamples.requireValid(ApiReferenceExamples.store(output), context);
    }
  }
}
