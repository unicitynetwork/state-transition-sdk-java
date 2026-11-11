package org.unicitylabs.sdk.functional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.StateMask;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationRule;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationStatus;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifierService;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.util.InclusionProofUtils;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.utils.TokenUtils;
import org.unicitylabs.sdk.utils.ExpiresAt;

/**
 * M-03: the inclusion-proof rule must bind the certification lock script and source state hash to
 * the transaction, not just the transaction hash. A transfer's hash covers only
 * {@code (recipient, stateMask, data)}, so two transfers from different source tokens with
 * identical recipient/mask/data collide on transaction hash while differing in lock script and
 * source state hash — exactly the substitution the binding check must reject.
 */
public class CertificationDataBindingTest {

  @Test
  public void rejectsCertificationDataFromADifferentTransaction() throws Exception {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    TrustBaseHistoryView trustBase = aggregatorClient.getTrustBase();
    StateTransitionClient client = new StateTransitionClient(aggregatorClient);
    PredicateVerifierService predicateVerifier = PredicateVerifierService.create();
    MintJustificationVerifierService mintJustificationVerifier =
            new MintJustificationVerifierService();
    VerificationContext context = new VerificationContext(trustBase, predicateVerifier,
            mintJustificationVerifier, new TokenIssuanceVerifierService(false));

    SigningService signingServiceA = SigningService.generate();
    SigningService signingServiceB = SigningService.generate();
    SignaturePredicate ownerA = SignaturePredicate.fromSigningService(signingServiceA);
    SignaturePredicate ownerB = SignaturePredicate.fromSigningService(signingServiceB);

    Token tokenA = TokenUtils.mintToken(client, context, ownerA);
    Token tokenB = TokenUtils.mintToken(client, context, ownerB);

    // Identical recipient, state mask and data → identical transaction hash across both sources.
    SignaturePredicate recipient = SignaturePredicate.fromSigningService(SigningService.generate());
    StateMask stateMask = StateMask.generate();

    TransferTransaction transferA = TransferTransaction.create(tokenA, recipient, stateMask, null, ExpiresAt.expiresAt());
    TransferTransaction transferB = TransferTransaction.create(tokenB, recipient, stateMask, null, ExpiresAt.expiresAt());

    Assertions.assertEquals(
            transferA.calculateTransactionHash(), transferB.calculateTransactionHash(),
            "precondition: the two transfers must share a transaction hash");
    Assertions.assertNotEquals(transferA.getLockScript(), transferB.getLockScript());

    // Certify transfer A and obtain its inclusion proof (carrying A's certification data).
    CertificationResponse response = client.submitCertificationRequest(
            CertificationData.fromTransaction(
                    transferA,
                    SignaturePredicateUnlockScript.create(transferA, signingServiceA))
    ).get();
    Assertions.assertEquals(CertificationStatus.SUCCESS, response.getStatus());

    InclusionProof proofA = InclusionProofUtils.waitInclusionProof(
            client, trustBase, predicateVerifier, transferA).get();

    long referenceTime = proofA.getReferenceTime();

    // A's certification data verifies against A...
    Assertions.assertEquals(
            InclusionProofVerificationStatus.OK,
            InclusionProofVerificationRule.verify(trustBase, predicateVerifier, proofA, transferA).getStatus());

    // ...but must be rejected when substituted onto B, which shares the transaction hash but has a
    // different lock script and source state hash.
    VerificationResult<InclusionProofVerificationStatus> result =
            InclusionProofVerificationRule.verify(trustBase, predicateVerifier, proofA, transferB);
    Assertions.assertEquals(
            InclusionProofVerificationStatus.CERTIFICATION_DATA_MISMATCH, result.getStatus());
  }
}
