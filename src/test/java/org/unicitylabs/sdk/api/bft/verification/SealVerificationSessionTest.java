package org.unicitylabs.sdk.api.bft.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
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
  void aLegacySuccessNeverAdmitsAStrictRequest() {
    TestChain.Authority epoch2 = new TestChain.Authority(NetworkId.LOCAL, 2, 10, 1);
    SealVerificationSession session = SealVerificationSession.create(epoch2.trustBase());
    UnicitySeal historical = seal(50, 3);

    // the historical proof of an earlier epoch passes the legacy quorum-only check and warms the memo
    assertEquals(VerificationStatus.OK, session.verifyLegacyQuorum(historical).getStatus());
    assertEquals(1, session.getStats().getQuorumChecks());

    // the strict request is refused on every call, hits or not, and costs no signature work
    for (int i = 0; i < 3; i++) {
      assertEquals(UnicityCertificateFailure.EPOCH_MISMATCH,
          session.authenticate(historical).getFailure());
    }
    assertEquals(1, session.getStats().getQuorumChecks());
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
  void aStakeWeightedAuthorityIsRefusedNotJudgedByTheWrongRule() {
    RootTrustBase weighted = RootTrustBase.fromJson("{\"version\":1,\"networkId\":3,\"epoch\":1,"
        + "\"epochStartRound\":1,\"rootNodes\":[{\"nodeId\":\"n0\",\"sigKey\":\"0x02aa\",\"stake\":5},"
        + "{\"nodeId\":\"n1\",\"sigKey\":\"0x02bb\",\"stake\":1}],\"quorumThreshold\":1,"
        + "\"stateHash\":\"\",\"changeRecordHash\":\"\",\"previousEntryHash\":\"\",\"signatures\":{}}");
    SealVerificationSession session = SealVerificationSession.create(weighted);
    assertEquals(UnicityCertificateFailure.UNSUPPORTED_AUTHORITY,
        session.authenticate(seal(5, 1)).getFailure());
    assertEquals(0, session.getStats().getQuorumChecks());
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
