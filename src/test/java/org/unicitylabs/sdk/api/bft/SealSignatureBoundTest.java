package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistory;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseMember;
import org.unicitylabs.sdk.api.bft.verification.NoTrustBaseEntryResult;
import org.unicitylabs.sdk.api.bft.verification.SealVerificationSession;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateFailure;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealQuorumSignaturesVerificationRule;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.Signature;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifierService;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Owner §49: a seal carries at most 256 supplied signatures, counted whoever signed, refused at
 * decode and again before the seal is hashed or any signature is looked at; a 256-member authority
 * is satisfied by its weighted threshold; the typed no-entry failure survives nested verification.
 */
class SealSignatureBoundTest {

  private static final long NOW = TestChain.TIMESTAMP;

  private static UnicitySeal sealWithStrangers(int strangers, SigningService[] signers,
      String[] ids, int validSigners) {
    UnicitySeal unsigned = new UnicitySeal(NetworkId.LOCAL, 5, 1, NOW, null, new byte[32], null);
    DataHash hash =
        new DataHasher(HashAlgorithm.SHA256).update(unsigned.toCborWithoutSignatures()).digest();
    Set<UnicitySeal.SignatureEntry> entries = new LinkedHashSet<>();
    for (int i = 0; i < strangers; i++) {
      entries.add(new UnicitySeal.SignatureEntry(String.format("stranger-%04d", i),
          Signature.decode(new byte[65])));
    }
    for (int i = 0; i < validSigners; i++) {
      entries.add(new UnicitySeal.SignatureEntry(ids[i], signers[i].sign(hash)));
    }
    return unsigned.withSignatures(entries);
  }

  private static SigningService[] signers(int count) {
    SigningService[] result = new SigningService[count];
    for (int i = 0; i < count; i++) {
      byte[] key = new byte[32];
      key[30] = (byte) ((i + 1) >> 8);
      key[31] = (byte) (i + 1);
      result[i] = new SigningService(key);
    }
    return result;
  }

  private static String[] ids(int count) {
    String[] result = new String[count];
    for (int i = 0; i < count; i++) {
      result[i] = String.format("node-%04d", i);
    }
    return result;
  }

  private static TrustBaseHistory committee(SigningService[] signers, String[] ids) {
    List<TrustBaseMember> members = new ArrayList<>();
    for (int i = 0; i < signers.length; i++) {
      members.add(new TrustBaseMember(ids[i], signers[i].getPublicKey(), 1));
    }
    return TrustBaseTestUtils.genesis(NetworkId.LOCAL, 1, 1, members);
  }

  @Test
  void aSealOf256SignaturesDecodesAndOf257IsRefusedAtDecode() {
    UnicitySeal at = sealWithStrangers(256, new SigningService[0], new String[0], 0);
    assertEquals(256, at.getWitnesses().size());
    assertEquals(256, UnicitySeal.fromCbor(at.toCbor()).getWitnesses().size());

    UnicitySeal over = sealWithStrangers(257, new SigningService[0], new String[0], 0);
    CborSerializationException e = assertThrows(CborSerializationException.class,
        () -> UnicitySeal.fromCbor(over.toCbor()));
    assertEquals("Map of more than 256 pairs.", e.getMessage());
  }

  @Test
  void the257thSignatureIsRefusedBySessionAndRuleBeforeAnySignatureWork() {
    SigningService[] signers = signers(4);
    String[] ids = ids(4);
    TrustBaseHistory history = committee(signers, ids);
    UnicitySeal over = sealWithStrangers(253, signers, ids, 4);
    assertEquals(257, over.getWitnesses().size());

    assertTrue(SealVerificationSession.checkSealStructure(over).contains("more than 256"));
    SealVerificationSession session = SealVerificationSession.create(history);
    assertEquals(UnicityCertificateFailure.MALFORMED_SEAL, session.authenticate(over).getFailure());
    assertEquals(0, session.getStats().getQuorumChecks());

    VerificationResult<VerificationStatus> rule = UnicitySealQuorumSignaturesVerificationRule
        .verify(history.getTip().get(), over);
    assertEquals(VerificationStatus.FAIL, rule.getStatus());
    assertTrue(rule.getMessage().contains("more than 256"), rule.getMessage());
    assertTrue(rule.getResults().isEmpty(), "no signature was looked at");
  }

  @Test
  void a256SignatureSealWithStrangersAndAQuorumIsAccepted() {
    SigningService[] signers = signers(4);
    String[] ids = ids(4);
    TrustBaseHistory history = committee(signers, ids);
    // 252 strangers first, then the four members: 256 supplied, all examined, quorum of 3 of 4 reached
    UnicitySeal seal = sealWithStrangers(252, signers, ids, 4);
    assertEquals(256, seal.getWitnesses().size());
    assertEquals(VerificationStatus.OK,
        SealVerificationSession.create(history).authenticate(seal).getStatus());
  }

  @Test
  void aSealOfAll256MembersOfA256MemberAuthorityReachesItsThresholdAndStopsThere() {
    SigningService[] signers = signers(256);
    String[] ids = ids(256);
    TrustBaseHistory history = committee(signers, ids);
    assertEquals(171, history.getTip().get().getQuorumThreshold());
    UnicitySeal seal = sealWithStrangers(0, signers, ids, 256);
    assertEquals(256, seal.getWitnesses().size());
    assertEquals(256, UnicitySeal.fromCbor(seal.toCbor()).getWitnesses().size());

    VerificationResult<VerificationStatus> result =
        UnicitySealQuorumSignaturesVerificationRule.verify(history.getTip().get(), seal);
    assertEquals(VerificationStatus.OK, result.getStatus());
    assertEquals(171, result.getResults().size(), "the rule stops at the threshold");
    assertEquals(VerificationStatus.OK, SealVerificationSession.create(history)
        .authenticate(UnicitySeal.fromCbor(seal.toCbor())).getStatus());

    // 170 members are not a quorum of 256
    UnicitySeal short1 = sealWithStrangers(0, signers, ids, 170);
    assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED,
        SealVerificationSession.create(history).authenticate(short1).getFailure());
  }

  @Test
  void theTypedNoEntryFailureSurvivesNestedTokenVerification() throws Exception {
    TestAggregatorClient aggregator = TestAggregatorClient.create();
    TrustBaseHistory held = aggregator.getTrustBase();
    StateTransitionClient client = new StateTransitionClient(aggregator);
    VerificationContext context = new VerificationContext(held, PredicateVerifierService.create(),
        new MintJustificationVerifierService(), new TokenIssuanceVerifierService(false));
    Token token = TokenUtils.mintToken(client, context,
        SignaturePredicate.fromSigningService(SigningService.generate()));

    // a verifier whose history lacks every entry: nothing is wrong with the token as far as is known
    TrustBaseHistory empty = TrustBaseHistory.create(held.getGenesisPin());
    VerificationContext emptyContext = new VerificationContext(empty,
        PredicateVerifierService.create(), new MintJustificationVerifierService(),
        new TokenIssuanceVerifierService(false));
    VerificationResult<VerificationStatus> result = token.verify(emptyContext);
    assertEquals(VerificationStatus.FAIL, result.getStatus());
    NoTrustBaseEntryResult noEntry = NoTrustBaseEntryResult.find(result);
    assertNotNull(noEntry, result.toString());
    assertEquals(1, noEntry.getEpoch());
    assertNull(noEntry.getHeldTipEpoch());

    // the same token verifies once the history holds the entry
    assertEquals(VerificationStatus.OK, token.verify(context).getStatus());
    assertNull(NoTrustBaseEntryResult.find(token.verify(context)));
  }
}
