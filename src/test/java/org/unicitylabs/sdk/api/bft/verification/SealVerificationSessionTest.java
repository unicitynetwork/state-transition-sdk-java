package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.bft.TrustBaseTestUtils;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.api.bft.TestChain;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

class SealVerificationSessionTest {

  private static final TestChain.Authority AUTHORITY = new TestChain.Authority();

  private static UnicitySeal seal(long round, int fill) {
    byte[] hash = new byte[32];
    java.util.Arrays.fill(hash, (byte) fill);
    return AUTHORITY.seal(round, hash, 0, 1, 2);
  }

  private static UnicitySeal sealBy(long round, int... nodes) {
    byte[] hash = new byte[32];
    java.util.Arrays.fill(hash, (byte) round);
    return AUTHORITY.seal(round, hash, nodes);
  }

  @Test
  void aGoodSealIsAuthenticatedStrictly() {
    SealVerificationSession session = SealVerificationSession.create(AUTHORITY.trustBase());
    SealVerificationResult result = session.authenticate(seal(5, 1));
    assertEquals(VerificationStatus.OK, result.getStatus());
    assertNull(result.getFailure());
    assertEquals(1, session.getStats().getQuorumChecks());
  }

  @Test
  void aRepeatedSealIsOneQuorumCheck() {
    SealVerificationSession session = SealVerificationSession.create(AUTHORITY.trustBase());
    UnicitySeal seal = seal(5, 1);
    for (int i = 0; i < 6; i++) {
      assertEquals(VerificationStatus.OK, session.authenticate(seal).getStatus());
    }
    assertEquals(1, session.getStats().getQuorumChecks());
    assertEquals(5, session.getStats().getMemoHits());
  }

  @Test
  void alteredWitnessesCannotHitAWarmEntry() {
    SealVerificationSession session = SealVerificationSession.create(AUTHORITY.trustBase());
    UnicitySeal good = seal(5, 1);
    assertEquals(VerificationStatus.OK, session.authenticate(good).getStatus());

    // the same seal with the witness of one signer replaced by garbage: below quorum
    Map<String, byte[]> witnesses = good.getWitnesses();
    String signer = witnesses.keySet().iterator().next();
    witnesses.put(signer, new byte[65]);
    UnicitySeal altered = withWitnesses(good, witnesses);
    SealVerificationResult result = session.authenticate(altered);
    assertEquals(VerificationStatus.FAIL, result.getStatus());
    assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED, result.getFailure());
    assertEquals(2, session.getStats().getQuorumChecks());

    // an additional, malformed witness gives no vote and no veto, but it is another seal
    Map<String, byte[]> extra = good.getWitnesses();
    extra.put("extra", new byte[] {1, 2, 3});
    SealVerificationResult withExtra = session.authenticate(withWitnesses(good, extra));
    assertEquals(VerificationStatus.OK, withExtra.getStatus());
    assertEquals(3, session.getStats().getQuorumChecks());
    // and the original is still a hit
    session.authenticate(good);
    assertEquals(3, session.getStats().getQuorumChecks());
  }

  @Test
  void aFailureIsNeverRemembered() {
    SealVerificationSession session = SealVerificationSession.create(AUTHORITY.trustBase());
    UnicitySeal belowQuorum = AUTHORITY.seal(5, new byte[32], 0, 1);
    for (int i = 0; i < 3; i++) {
      assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED,
          session.authenticate(belowQuorum).getFailure());
    }
    assertEquals(3, session.getStats().getQuorumChecks());
    assertEquals(0, session.getStats().getMemoHits());
  }

  @Test
  void theOldestSealIsEvictedFromAMemoOfOne() {
    SealVerificationSession session =
        SealVerificationSession.create(AUTHORITY.trustBase(), 1, 4);
    UnicitySeal a = seal(5, 1);
    UnicitySeal b = seal(6, 2);
    session.authenticate(a);
    session.authenticate(b);
    session.authenticate(a);
    assertEquals(3, session.getStats().getQuorumChecks());
    session.authenticate(a);
    assertEquals(3, session.getStats().getQuorumChecks());
  }

  @Test
  void theStrictPathChecksNetworkEpochAndRoundBeforeTheMemoEveryTime() {
    TestChain.Authority epoch2 = new TestChain.Authority(NetworkId.LOCAL, 2, 10, 1);
    SealVerificationSession session = SealVerificationSession.create(epoch2.trustBase());
    // sealed by the same keys, but in epoch 1
    UnicitySeal oldEpoch = seal(50, 3);
    assertEquals(UnicityCertificateFailure.EPOCH_MISMATCH,
        session.authenticate(oldEpoch).getFailure());
    assertEquals(0, session.getStats().getQuorumChecks());

    UnicitySeal beforeStart = epoch2.seal(9, new byte[32], 0, 1, 2);
    assertEquals(UnicityCertificateFailure.BEFORE_EPOCH_START,
        session.authenticate(beforeStart).getFailure());

    TestChain.Authority otherNetwork = new TestChain.Authority(NetworkId.MAINNET, 2, 10, 1);
    assertEquals(UnicityCertificateFailure.NETWORK_MISMATCH,
        session.authenticate(otherNetwork.seal(50, new byte[32], 0, 1, 2)).getFailure());
  }

  @Test
  void anEpochEndsExclusivelyAtTheStartRoundOfTheNextOne() {
    TestChain.Authority epoch2 = new TestChain.Authority(NetworkId.LOCAL, 2, 10, 1);
    SealVerificationSession session =
        SealVerificationSession.create(AUTHORITY.trustBaseThrough(epoch2));
    assertEquals(VerificationStatus.OK, session.authenticate(seal(9, 1)).getStatus());
    assertEquals(UnicityCertificateFailure.AFTER_EPOCH_END,
        session.authenticate(seal(10, 1)).getFailure());
    // the first round of epoch 2 belongs to epoch 2
    assertEquals(VerificationStatus.OK,
        session.authenticate(epoch2.seal(10, new byte[32], 0, 1, 2)).getStatus());
  }

  @Test
  void aMemoHitStillPlacesTheSealInTheGrowingHistory() {
    TestChain.Authority epoch2 = new TestChain.Authority(NetworkId.LOCAL, 2, 10, 1);
    org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistory history = AUTHORITY.trustBase();
    SealVerificationSession session = SealVerificationSession.create(history);
    UnicitySeal inEpoch1 = seal(12, 3);

    assertEquals(VerificationStatus.OK, session.authenticate(inEpoch1).getStatus());
    assertEquals(1, session.getStats().getQuorumChecks());

    // the history grows: epoch 2 starts at round 10, so round 12 of epoch 1 is past its end
    AUTHORITY.appendSuccessor(history, epoch2);
    for (int i = 0; i < 3; i++) {
      assertEquals(UnicityCertificateFailure.AFTER_EPOCH_END,
          session.authenticate(inEpoch1).getFailure());
    }
    // refused on every call from the placement: no signature work, and the memo was not consulted
    assertEquals(1, session.getStats().getQuorumChecks());
    assertEquals(0, session.getStats().getMemoHits());
  }

  @Test
  void aSealWithNoRoundOrAnEarlyTimestampOrNoSignaturesIsMalformed() {
    SealVerificationSession session = SealVerificationSession.create(AUTHORITY.trustBase());
    assertEquals(UnicityCertificateFailure.MALFORMED_SEAL,
        session.authenticate(AUTHORITY.sign(TestChain.rawSeal(NetworkId.LOCAL, 0, 1,
            TestChain.TIMESTAMP, new byte[32], false), 0, 1, 2)).getFailure());
    assertEquals(UnicityCertificateFailure.MALFORMED_SEAL,
        session.authenticate(AUTHORITY.sign(TestChain.rawSeal(NetworkId.LOCAL, 5, 1,
            SealVerificationSession.GENESIS_TIME - 1, new byte[32], false), 0, 1, 2))
            .getFailure());
    assertEquals(UnicityCertificateFailure.MALFORMED_SEAL, session.authenticate(
        TestChain.rawSeal(NetworkId.LOCAL, 5, 1, TestChain.TIMESTAMP, new byte[32], false))
        .getFailure());
    assertEquals(UnicityCertificateFailure.MALFORMED_SEAL, session.authenticate(
        TestChain.rawSeal(NetworkId.LOCAL, 5, 1, TestChain.TIMESTAMP, new byte[0], true))
        .getFailure());
    assertEquals(UnicityCertificateFailure.MALFORMED_SEAL, session.authenticate(
        TestChain.rawSeal(NetworkId.LOCAL, 5, 1, TestChain.TIMESTAMP, new byte[32], true))
        .getFailure());
    // the timestamp is compared as an unsigned number: 2^64-1 is late, not negative
    assertEquals(VerificationStatus.OK, session.authenticate(AUTHORITY.sign(
        TestChain.rawSeal(NetworkId.LOCAL, 5, 1, -1L, new byte[32], false), 0, 1, 2))
        .getStatus());
  }

  @Test
  void epochsAndRoundsAreCompared2To63AndAbove() {
    // epoch 2^63, started at round 2^63
    TestChain.Authority high = new TestChain.Authority(NetworkId.LOCAL, Long.MIN_VALUE,
        Long.MIN_VALUE, 1);
    SealVerificationSession session = SealVerificationSession.create(high.trustBase());
    assertEquals(VerificationStatus.OK,
        session.authenticate(high.seal(Long.MIN_VALUE, new byte[32], 0, 1, 2)).getStatus());
    assertEquals(VerificationStatus.OK,
        session.authenticate(high.seal(-1L, new byte[32], 0, 1, 2)).getStatus());
    // 2^63 - 1 is below the start although it is "above" it as a signed number
    assertEquals(UnicityCertificateFailure.BEFORE_EPOCH_START,
        session.authenticate(high.seal(Long.MAX_VALUE, new byte[32], 0, 1, 2)).getFailure());
    // the epoch 2^64-1 is not 2^63
    TestChain.Authority max = new TestChain.Authority(NetworkId.LOCAL, -1L, 1, 1);
    assertEquals(UnicityCertificateFailure.EPOCH_MISMATCH,
        SealVerificationSession.create(max.trustBase()).authenticate(
            high.seal(Long.MIN_VALUE, new byte[32], 0, 1, 2)).getFailure());
  }

  @Test
  void aWeightedAuthorityCountsWeightNotSigners() {
    // weights 5,1,1,1: threshold 6; node 0 alone has 5, nodes 0 and 1 have 6, nodes 1-3 have 3
    SealVerificationSession session =
        SealVerificationSession.create(AUTHORITY.weightedTrustBase(5, 1, 1, 1));
    assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED,
        session.authenticate(sealBy(5, 0)).getFailure());
    assertEquals(VerificationStatus.OK, session.authenticate(sealBy(5, 0, 1)).getStatus());
    assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED,
        session.authenticate(sealBy(6, 1, 2, 3)).getFailure());
  }

  @Test
  void anotherAuthorityIsAnotherIdentityAndNeverSharesAVerdict() {
    SealVerificationSession a = SealVerificationSession.create(AUTHORITY.trustBase());
    SealVerificationSession b = SealVerificationSession.create(
        new TestChain.Authority(NetworkId.LOCAL, 1, 1, 2).trustBase());
    assertEquals(true, !a.getIdentity().equals(b.getIdentity()));
    assertEquals(a.getIdentity(),
        SealVerificationSession.create(AUTHORITY.trustBase()).getIdentity());
    assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED,
        b.authenticate(seal(5, 1)).getFailure());
  }

  @Test
  void limitsMustBePositive() {
    assertThrows(IllegalArgumentException.class,
        () -> SealVerificationSession.create(AUTHORITY.trustBase(), 0, 1));
    assertThrows(IllegalArgumentException.class,
        () -> SealVerificationSession.create(AUTHORITY.trustBase(), 1, 0));
  }

  private static UnicitySeal withWitnesses(UnicitySeal seal, Map<String, byte[]> witnesses) {
    // the seal bytes with another signature map: encode, then decode the changed encoding
    Set<CborMap.Entry> entries = new LinkedHashSet<>();
    for (Map.Entry<String, byte[]> witness : new LinkedHashMap<>(witnesses).entrySet()) {
      entries.add(new CborMap.Entry(CborSerializer.encodeTextString(witness.getKey()),
          CborSerializer.encodeByteString(witness.getValue())));
    }
    byte[] unsignedPart = seal.toCborWithoutSignatures();
    // tag + array header of 8 elements, then the seven fixed elements and the null signature map
    byte[] withMap = replaceTrailingNull(unsignedPart, CborSerializer.encodeMap(new CborMap(entries)));
    return UnicitySeal.fromCbor(withMap);
  }

  private static byte[] replaceTrailingNull(byte[] bytes, byte[] replacement) {
    byte[] result = new byte[bytes.length - 1 + replacement.length];
    System.arraycopy(bytes, 0, result, 0, bytes.length - 1);
    System.arraycopy(replacement, 0, result, bytes.length - 1, replacement.length);
    return result;
  }

  @SuppressWarnings("unused")
  private static DataHash hash(byte[] data) {
    return new DataHasher(HashAlgorithm.SHA256).update(data).digest();
  }
}
