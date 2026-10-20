package org.unicitylabs.sdk.examples;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.transaction.StateMask;
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

    Map<String, byte[]> journal = new HashMap<>();
    Token token = ApiReferenceExamples.mint(client, context, aliceOwner, new byte[] {1, 2, 3},
        journal);
    Token sent = ApiReferenceExamples.transfer(client, context, token, alice, bobOwner, journal);

    // recovery after a crash: the persisted transactions are submitted again and give the same
    // tokens, whether or not they were certified in the meantime
    assertEquals(token.getId(),
        ApiReferenceExamples.recoverMint(client, context, journal).getId());
    assertEquals(sent.getLatestTransaction().calculateStateHash(),
        ApiReferenceExamples.recoverTransfer(client, context, token, journal)
            .getLatestTransaction().calculateStateHash());
    Token received = ApiReferenceExamples.store(sent);

    ApiReferenceExamples.requireValid(received, context);
    assertTrue(ApiReferenceExamples.isMine(received, bobOwner));
    assertEquals(1, received.getTransactions().size());
    assertTrue(ApiReferenceExamples.transferCount(received).endsWith("has 1 transfers"));
    assertEquals(received.getId(),
        ApiReferenceExamples.transfer(client, context, received, bob, aliceOwner, new HashMap<>())
            .getId());
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
    Map<String, byte[]> journal = new HashMap<>();
    Token source = ApiReferenceExamples.mintPayment(client, context, predicate,
        ApiReferenceExamples.assets(), journal);

    SigningService other = SigningService.generate();
    var requests = ApiReferenceExamples.splitRequests(predicate,
        SignaturePredicate.fromSigningService(other));
    var plan = ApiReferenceExamples.planSplit(source, requests, StateMask.generate(), 4_000_000_000L,
        journal);
    List<Token> outputs = ApiReferenceExamples.split(client, context, source, owner, plan, journal);

    assertEquals(2, outputs.size());
    for (Token output : outputs) {
      ApiReferenceExamples.requireValid(ApiReferenceExamples.store(output), context);
    }

    // the persisted inputs rebuild the identical burn
    var recovered = ApiReferenceExamples.recoverSplit(source, 2, journal);
    assertArrayEquals(plan.getBurnTransaction().toCbor(), recovered.getBurnTransaction().toCbor());
  }
}
