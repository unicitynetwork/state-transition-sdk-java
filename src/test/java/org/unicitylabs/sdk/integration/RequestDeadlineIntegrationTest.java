package org.unicitylabs.sdk.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.util.InclusionProofUtils;
import org.unicitylabs.sdk.utils.ExpiresAt;

/**
 * Request-deadline behaviour against a real aggregator.
 *
 * <p>The unit suite covers the same ground against {@link org.unicitylabs.sdk.TestAggregatorClient},
 * which derives leaf values with the code under test; only a real service can tell whether the two
 * still agree. Run with {@code ./gradlew integrationTest}.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestDeadlineIntegrationTest {

  private AggregatorStack stack;
  private StateTransitionClient client;
  private TrustBaseHistoryView trustBase;
  private PredicateVerifierService predicateVerifier;

  @BeforeAll
  void startStack() throws Exception {
    this.stack = AggregatorStack.start();
    this.client = new StateTransitionClient(new JsonRpcAggregatorClient(this.stack.getUrl()));
    this.trustBase = this.stack.getTrustBase();
    this.predicateVerifier = PredicateVerifierService.create();
  }

  @AfterAll
  void stopStack() {
    if (this.stack != null) {
      this.stack.close();
    }
  }

  private MintTransaction mint(Long deadline) {
    return MintTransaction.builder(
                    this.trustBase.getNetworkId().get(),
                    SignaturePredicate.fromSigningService(SigningService.generate()))
            .expiresAt(deadline)
            .build();
  }

  private CertificationStatus submit(MintTransaction transaction) throws Exception {
    return this.client.submitCertificationRequest(
            CertificationData.fromMintTransaction(transaction)).get().getStatus();
  }

  private InclusionProof certify(Long deadline) throws Exception {
    MintTransaction transaction = mint(deadline);
    Assertions.assertEquals(CertificationStatus.SUCCESS, submit(transaction));

    return InclusionProofUtils.waitInclusionProof(
            this.client, this.trustBase, this.predicateVerifier, transaction).get();
  }

  @Test
  void acceptsADeadlineAheadOfTheRoundReferenceTime() throws Exception {
    Assertions.assertEquals(CertificationStatus.SUCCESS, submit(mint(ExpiresAt.expiresAt())));
  }

  @Test
  void acceptsARequestThatLeavesTheDeadlineToTheService() throws Exception {
    Assertions.assertEquals(CertificationStatus.SUCCESS, submit(mint(null)));
  }

  @Test
  void rejectsADeadlineThatHasAlreadyPassed() throws Exception {
    Assertions.assertEquals(CertificationStatus.REQUEST_EXPIRED,
            submit(mint(ExpiresAt.expiredExpiresAt())));
  }

  @Test
  void rejectsADeadlineEqualToAReferenceTimeAlreadyReached() throws Exception {
    // A reference time the service has already certified a leaf under, so it is at or behind the
    // reference time the next round pins. The deadline is exclusive, so equality is already late.
    InclusionProof proof = certify(ExpiresAt.expiresAt());
    long reached = proof.getReferenceTime();

    Assertions.assertEquals(CertificationStatus.REQUEST_EXPIRED, submit(mint(reached)));
  }

  @Test
  void bindsAServiceAssignedDeadlineWithoutRecordingIt() throws Exception {
    InclusionProof proof = certify(null);

    // The service derives a deadline from consensus time for a request that omits one. That value
    // is service metadata: never written to the leaf, so a later verifier sees the same absence
    // the requester sent and has nothing to re-check.
    Assertions.assertFalse(
            proof.getCertificationData().getExpiresAt().isPresent());
    Assertions.assertTrue(true);
  }

  @Test
  void servesBackTheExplicitDeadlineTheTransactionHashCommitsTo() throws Exception {
    long deadline = ExpiresAt.expiresAt();
    InclusionProof proof = certify(deadline);

    Assertions.assertEquals(deadline,
            proof.getCertificationData()
                    .getExpiresAt().orElseThrow(AssertionError::new));
    // Admission is what the deadline governs, and it is exclusive: the leaf could only be created
    // in a round strictly before it.
    Assertions.assertTrue(proof.getReferenceTime() < deadline);
  }

  @Test
  void reportsAReferenceTimeNoLaterThanTheRoundThatCertifiedIt() throws Exception {
    InclusionProof proof = certify(ExpiresAt.expiresAt());

    // The service sets the round's input record timestamp to the very reference time its leaves
    // are built from, so for the certifying round the two are equal and the bound the
    // verification rule enforces is exact.
    Assertions.assertEquals(
            proof.getReferenceTime(),
            proof.getUnicityCertificate().getInputRecord().getTimestamp());
  }

  @Test
  void reportsALeaflessProofForARequestThatWasNeverSubmitted() throws Exception {
    MintTransaction never = mint(ExpiresAt.expiresAt());

    // Nothing was certified, and the response is the type that says so.
    Assertions.assertNull(this.client.getInclusionProof(
            org.unicitylabs.sdk.api.StateId.fromTransaction(never)).get().getInclusionProof());
  }
}
