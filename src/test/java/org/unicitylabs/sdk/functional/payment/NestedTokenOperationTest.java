package org.unicitylabs.sdk.functional.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.verification.OperationUnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.OperationVerificationStats;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.payment.SplitMintJustification;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.payment.SplitResult;
import org.unicitylabs.sdk.payment.SplitToken;
import org.unicitylabs.sdk.payment.SplitTokenRequest;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.payment.asset.PaymentAssetCollection;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.CertifiedTransferTransaction;
import org.unicitylabs.sdk.transaction.StateMask;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifierService;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.ExpiresAt;
import org.unicitylabs.sdk.utils.TokenUtils;

/** One operation spans a token and the tokens nested in its mint justification. */
class NestedTokenOperationTest {

  private static Set<String> seals(Token token) {
    Set<String> seals = new HashSet<>();
    seals.add(seal(token.getGenesis().getInclusionProof()));
    for (CertifiedTransferTransaction transfer : token.getTransactions()) {
      seals.add(seal(transfer.getInclusionProof()));
    }
    return seals;
  }

  private static String seal(InclusionProof proof) {
    return HexConverter.encode(proof.getUnicityCertificate().getUnicitySeal().toCbor());
  }

  @Test
  void nestedProofsAreCheckedOncePerDistinctSealAndCertificate() throws Exception {
    TestAggregatorClient aggregator = TestAggregatorClient.create();
    StateTransitionClient client = new StateTransitionClient(aggregator);
    MintJustificationVerifierService justifications = new MintJustificationVerifierService();
    justifications.register(new SplitMintJustificationVerifier(TestPaymentData::decode));
    VerificationContext context = new VerificationContext(aggregator.getTrustBase(),
        PredicateVerifierService.create(), justifications, new TokenIssuanceVerifierService(false));

    SigningService owner = SigningService.generate();
    SignaturePredicate predicate = SignaturePredicate.fromSigningService(owner);
    Asset asset1 = new Asset(new AssetId("A1".getBytes(StandardCharsets.UTF_8)),
        BigInteger.valueOf(5));
    Asset asset2 = new Asset(new AssetId("A2".getBytes(StandardCharsets.UTF_8)),
        BigInteger.valueOf(5));
    Token source = TokenUtils.mintToken(client, context, predicate,
        new TestPaymentData(PaymentAssetCollection.create(asset1, asset2)).encode());
    SplitResult split = TokenSplit.split(source, TestPaymentData::decode, List.of(
        SplitTokenRequest.create(predicate, new TestPaymentData(PaymentAssetCollection.create(asset1))),
        SplitTokenRequest.create(predicate, new TestPaymentData(PaymentAssetCollection.create(asset2)))),
        StateMask.generate(), ExpiresAt.expiresAt());
    Token burn = TokenUtils.transferToken(client, context, source, split.getBurnTransaction(),
        SignaturePredicateUnlockScript.create(split.getBurnTransaction(), owner));
    SplitToken output = split.getTokens().get(0);
    Token minted = TokenUtils.mintToken(client, context, output.getRecipient(),
        output.getPaymentData().encode(), output.getNetworkId(), output.getTokenType(),
        output.getSalt(), SplitMintJustification.create(burn, output.getProofs()).toCbor());

    OperationUnicityCertificateVerifier operation = (OperationUnicityCertificateVerifier)
        new UnicityCertificateVerifier().beginOperation(VerificationPolicy.LEGACY_QUORUM,
            PartitionViewPolicy.none());
    VerificationContext scoped = context.withCertificateVerifier(operation);

    // the output and the burned source it nests: distinct seals of both
    Set<String> distinct = new HashSet<>(seals(minted));
    distinct.addAll(seals(burn));
    assertTrue(distinct.size() > seals(minted).size(), "the nested token adds seals");

    assertEquals(VerificationStatus.OK, minted.verify(scoped).getStatus());
    OperationVerificationStats stats = operation.getStats();
    assertEquals(distinct.size(), stats.getQuorumChecks(),
        "one quorum check per distinct seal across the token and its nested token");
    long bodies = stats.getBodyChecks();

    // verifying the nested token again, or the whole token again, in the same operation is free
    assertEquals(VerificationStatus.OK, burn.verify(scoped).getStatus());
    assertEquals(VerificationStatus.OK, minted.verify(scoped).getStatus());
    OperationVerificationStats after = operation.getStats();
    assertEquals(distinct.size(), after.getQuorumChecks());
    assertEquals(bodies, after.getBodyChecks());
    assertTrue(after.getBodyHits() > stats.getBodyHits());
  }
}
