package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.verification.OperationUnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.OperationVerificationStats;
import org.unicitylabs.sdk.api.bft.verification.SealVerificationSession;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateFailure;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerificationResult;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

class OperationVerifierTest {

  private static final TestChain.Authority AUTHORITY = new TestChain.Authority();
  private static final long NOW = TestChain.TIMESTAMP;

  private static OperationUnicityCertificateVerifier operation(VerificationPolicy policy,
      PartitionViewPolicy pins) {
    return (OperationUnicityCertificateVerifier) new UnicityCertificateVerifier()
        .beginOperation(policy, pins);
  }

  private static OperationUnicityCertificateVerifier strict() {
    return operation(VerificationPolicy.STRICT, null);
  }

  private static UnicityCertificate withWitnesses(UnicityCertificate certificate,
      Map<String, byte[]> witnesses) {
    List<CborMap.Entry> entries = new ArrayList<>();
    for (Map.Entry<String, byte[]> witness : witnesses.entrySet()) {
      entries.add(new CborMap.Entry(CborSerializer.encodeTextString(witness.getKey()),
          CborSerializer.encodeByteString(witness.getValue())));
    }
    byte[] unsigned = certificate.getUnicitySeal().toCborWithoutSignatures();
    byte[] map = CborSerializer.encodeMap(new CborMap(new java.util.LinkedHashSet<>(entries)));
    byte[] sealBytes = new byte[unsigned.length - 1 + map.length];
    System.arraycopy(unsigned, 0, sealBytes, 0, unsigned.length - 1);
    System.arraycopy(map, 0, sealBytes, unsigned.length - 1, map.length);
    UnicitySeal seal = UnicitySeal.fromCbor(sealBytes);
    return new UnicityCertificate(certificate.getInputRecord(), certificate.getTechnicalRecordHash(),
        certificate.getShardConfigurationHash(), certificate.getShardTreeCertificate(),
        certificate.getUnicityTreeCertificate(), seal);
  }

  @Test
  void manyProofsOfOneSealCostOneQuorumCheckAndOneBodyCheckPerDistinctCertificate() {
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(1, NOW));
    OperationUnicityCertificateVerifier verifier = strict();
    RootTrustBase trustBase = AUTHORITY.trustBase();

    // 20 proofs: five of each of four distinct certificates, one shared seal
    for (int proof = 0; proof < 20; proof++) {
      String shard = new String[] {"00", "01", "10", "11"}[proof % 4];
      UnicityCertificateVerificationResult result =
          verifier.verifyCertificate(trustBase, sealed.certificate(shard));
      assertEquals(VerificationStatus.OK, result.getStatus());
      assertNull(result.getFailure());
    }

    OperationVerificationStats stats = verifier.getStats();
    assertEquals(1, stats.getQuorumChecks(), "one quorum check for the one shared seal");
    assertEquals(4, stats.getBodyChecks(), "one body check per distinct certificate");
    assertEquals(16, stats.getBodyHits());
    assertEquals(20, stats.getEncodes());
    assertEquals(1, stats.getAuthorityBuilds());
  }

  @Test
  void aRejectionBetweenTwoSuccessesNeitherPoisonsNorFlushesTheMemo() {
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(1, NOW));
    TestChain.Sealed other = TestChain.seal(AUTHORITY, 7, 10, TestChain.fourShards(2, NOW), 1, 2, 3);
    OperationUnicityCertificateVerifier verifier = strict();
    RootTrustBase trustBase = AUTHORITY.trustBase();

    assertEquals(VerificationStatus.OK,
        verifier.verifyCertificate(trustBase, sealed.certificate("00")).getStatus());
    // below quorum: a seal signed by only two nodes
    UnicityCertificate weak = TestChain.seal(AUTHORITY, 7, 11, TestChain.fourShards(3, NOW), 0, 1)
        .certificate("00");
    assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED,
        verifier.verifyCertificate(trustBase, weak).getFailure());
    assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED,
        verifier.verifyCertificate(trustBase, weak).getFailure());
    assertEquals(VerificationStatus.OK,
        verifier.verifyCertificate(trustBase, other.certificate("01")).getStatus());

    long before = verifier.getStats().getQuorumChecks();
    assertEquals(VerificationStatus.OK,
        verifier.verifyCertificate(trustBase, sealed.certificate("00")).getStatus());
    assertEquals(VerificationStatus.OK,
        verifier.verifyCertificate(trustBase, other.certificate("01")).getStatus());
    assertEquals(before, verifier.getStats().getQuorumChecks(), "both successes are still hits");
    // the failure was checked twice: failures are not remembered
    assertEquals(4, before);
  }

  @Test
  void alteredWitnessesAfterAWarmCacheAreAMissAndAreChecked() {
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(1, NOW));
    OperationUnicityCertificateVerifier verifier = strict();
    RootTrustBase trustBase = AUTHORITY.trustBase();
    UnicityCertificate good = sealed.certificate("00");
    assertEquals(VerificationStatus.OK, verifier.verifyCertificate(trustBase, good).getStatus());

    Map<String, byte[]> witnesses = new LinkedHashMap<>(good.getUnicitySeal().getWitnesses());
    String signer = witnesses.keySet().iterator().next();
    witnesses.put(signer, new byte[65]);
    UnicityCertificate altered = withWitnesses(good, witnesses);

    UnicityCertificateVerificationResult result = verifier.verifyCertificate(trustBase, altered);
    assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED, result.getFailure());
    OperationVerificationStats stats = verifier.getStats();
    // the altered certificate is another complete certificate: its body is checked on its own
    assertEquals(2, stats.getBodyChecks());
    assertEquals(2, stats.getQuorumChecks());
  }

  @Test
  void aCertificateOfAnotherPartitionIsRefusedBeforeAnyMemoLookup() {
    TestChain.Sealed seven = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(1, NOW));
    TestChain.Sealed eight = TestChain.seal(AUTHORITY, 8, 9, TestChain.fourShards(1, NOW));
    OperationUnicityCertificateVerifier verifier = strict();
    RootTrustBase trustBase = AUTHORITY.trustBase();
    assertEquals(VerificationStatus.OK,
        verifier.verifyCertificate(trustBase, seven.certificate("00")).getStatus());

    UnicityCertificateVerificationResult result =
        verifier.verifyCertificate(trustBase, eight.certificate("00"));
    assertEquals(UnicityCertificateFailure.PARTITION_MISMATCH, result.getFailure());
    assertEquals(1, verifier.getStats().getBodyChecks(), "no body check, no quorum check");
    assertEquals(1, verifier.getStats().getQuorumChecks());

    // a valid partition-7 certificate re-labelled as partition 8 keeps its seal and loses its path
    UnicityCertificate relabelled = new UnicityCertificate(
        seven.certificate("00").getInputRecord(), seven.certificate("00").getTechnicalRecordHash(),
        seven.certificate("00").getShardConfigurationHash(),
        seven.certificate("00").getShardTreeCertificate(), new UnicityTreeCertificate(8,
        List.of()), seven.certificate("00").getUnicitySeal());
    assertEquals(UnicityCertificateFailure.PARTITION_MISMATCH,
        verifier.verifyCertificate(trustBase, relabelled).getFailure());
  }

  @Test
  void aLegacyOperationAcceptsAHistoricalEpochAStrictOneRefusesIt() {
    TestChain.Authority epoch2 = new TestChain.Authority(NetworkId.LOCAL, 2, 10, 1);
    TestChain.Sealed historical = TestChain.seal(AUTHORITY, 7, 50, TestChain.fourShards(1, NOW));
    RootTrustBase current = epoch2.trustBase();

    OperationUnicityCertificateVerifier legacy = operation(VerificationPolicy.LEGACY_QUORUM, null);
    assertEquals(VerificationStatus.OK,
        legacy.verifyCertificate(current, historical.certificate("00")).getStatus());
    // the same operation's session is the strict authenticator, and the legacy success does not
    // admit a strict request for the same seal
    SealVerificationSession session = legacy.sessionFor(current);
    assertEquals(UnicityCertificateFailure.EPOCH_MISMATCH,
        session.authenticate(historical.certificate("00").getUnicitySeal()).getFailure());
    assertEquals(1, legacy.getStats().getQuorumChecks());

    OperationUnicityCertificateVerifier strict = strict();
    UnicityCertificateVerificationResult refused =
        strict.verifyCertificate(current, historical.certificate("00"));
    assertEquals(UnicityCertificateFailure.EPOCH_MISMATCH, refused.getFailure());
    assertTrue(refused.getFailure().isAuthorityRejection());
    assertEquals(0, strict.getStats().getQuorumChecks());
  }

  @Test
  void anotherAuthorityIsAnotherSessionAndNeverSharesAVerdict() {
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(1, NOW));
    OperationUnicityCertificateVerifier verifier = operation(VerificationPolicy.LEGACY_QUORUM, null);
    RootTrustBase same = AUTHORITY.trustBase();
    RootTrustBase otherEpoch = new TestChain.Authority(NetworkId.LOCAL, 7, 1, 1).trustBase();
    RootTrustBase otherKeys = new TestChain.Authority(NetworkId.LOCAL, 1, 1, 2).trustBase();

    assertEquals(VerificationStatus.OK,
        verifier.verifyCertificate(same, sealed.certificate("00")).getStatus());
    // same keys, other epoch: the legacy verdict is a property of the keys, but the authority
    // content differs, so it is a new identity and a new quorum check
    assertEquals(VerificationStatus.OK,
        verifier.verifyCertificate(otherEpoch, sealed.certificate("00")).getStatus());
    assertEquals(2, verifier.getStats().getQuorumChecks());
    assertEquals(2, verifier.getStats().getAuthorityBuilds());
    // other keys: not a quorum of this authority
    assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED,
        verifier.verifyCertificate(otherKeys, sealed.certificate("00")).getFailure());
    assertEquals(3, verifier.getStats().getAuthorityBuilds());
  }

  @Test
  void theNetworkIsComparedOnEveryCall() {
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(1, NOW));
    OperationUnicityCertificateVerifier verifier = strict();
    assertEquals(VerificationStatus.OK,
        verifier.verifyCertificate(AUTHORITY.trustBase(), sealed.certificate("00")).getStatus());
    RootTrustBase mainnet = new TestChain.Authority(NetworkId.MAINNET, 1, 1, 1).trustBase();
    assertEquals(UnicityCertificateFailure.NETWORK_MISMATCH,
        verifier.verifyCertificate(mainnet, sealed.certificate("00")).getFailure());
  }

  @Test
  void pinsOfTheOperationConstrainShardAndConfiguration() {
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(1, NOW));
    UnicityCertificate certificate = sealed.certificate("00");
    RootTrustBase trustBase = AUTHORITY.trustBase();

    PartitionViewPolicy right = PartitionViewPolicy.none().withPins(List.of(
        new ShardConfigurationPin(ShardId.fromBitString("00"),
            certificate.getShardConfigurationHash())));
    assertEquals(VerificationStatus.OK, operation(VerificationPolicy.STRICT, right)
        .verifyCertificate(trustBase, certificate).getStatus());

    PartitionViewPolicy wrong = PartitionViewPolicy.none().withPins(List.of(
        new ShardConfigurationPin(ShardId.fromBitString("00"), new byte[32])));
    assertEquals(UnicityCertificateFailure.PIN_MISMATCH, operation(VerificationPolicy.STRICT, wrong)
        .verifyCertificate(trustBase, certificate).getFailure());

    PartitionViewPolicy otherScheme = PartitionViewPolicy.none().withScheme(List.of(
        ShardId.fromBitString("0"), ShardId.fromBitString("1")));
    assertEquals(UnicityCertificateFailure.PIN_MISMATCH,
        operation(VerificationPolicy.STRICT, otherScheme)
            .verifyCertificate(trustBase, certificate).getFailure());
  }

  @Test
  void aCustomVerifierIsNeverWrappedAndAnOperationReturnsItselfForTheSameRequest() {
    UnicityCertificateVerifier custom = new UnicityCertificateVerifier() { };
    assertSame(custom, custom.beginOperation(VerificationPolicy.STRICT, null));

    OperationUnicityCertificateVerifier operation = strict();
    assertSame(operation, operation.beginOperation(VerificationPolicy.STRICT, null));
    assertSame(operation, operation.beginOperation(VerificationPolicy.STRICT,
        PartitionViewPolicy.none()));
    assertNotSame(operation, operation.beginOperation(VerificationPolicy.LEGACY_QUORUM, null));
    assertNotSame(operation, new UnicityCertificateVerifier()
        .beginOperation(VerificationPolicy.STRICT, null));
  }

  @Test
  void theTypedFailureNamesEachCauseAndAnAuthorityRejectionIsDistinct() {
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(1, NOW));
    UnicityCertificate good = sealed.certificate("00");
    OperationUnicityCertificateVerifier verifier = strict();
    RootTrustBase trustBase = AUTHORITY.trustBase();

    UnicityCertificate badPath = new UnicityCertificate(good.getInputRecord(),
        good.getTechnicalRecordHash(), good.getShardConfigurationHash(),
        new ShardTreeCertificate(good.getShardTreeCertificate().getShard(),
            List.of(new byte[32], new byte[32])), good.getUnicityTreeCertificate(),
        good.getUnicitySeal());
    UnicityCertificateVerificationResult hashMismatch = verifier.verifyCertificate(trustBase, badPath);
    assertEquals(UnicityCertificateFailure.SEAL_HASH_MISMATCH, hashMismatch.getFailure());
    assertTrue(!hashMismatch.getFailure().isAuthorityRejection());

    UnicityCertificate badTechnical = new UnicityCertificate(good.getInputRecord(), new byte[5],
        good.getShardConfigurationHash(), good.getShardTreeCertificate(),
        good.getUnicityTreeCertificate(), good.getUnicitySeal());
    assertEquals(UnicityCertificateFailure.BODY_INVALID,
        verifier.verifyCertificate(trustBase, badTechnical).getFailure());

    UnicityCertificate shortPath = new UnicityCertificate(good.getInputRecord(),
        good.getTechnicalRecordHash(), good.getShardConfigurationHash(),
        new ShardTreeCertificate(good.getShardTreeCertificate().getShard(), List.of()),
        good.getUnicityTreeCertificate(), good.getUnicitySeal());
    assertEquals(UnicityCertificateFailure.SHARD_PATH_INVALID,
        verifier.verifyCertificate(trustBase, shortPath).getFailure());

    UnicityCertificate unsealed = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(1, NOW),
        0, 1).certificate("00");
    assertTrue(verifier.verifyCertificate(trustBase, unsealed).getFailure()
        .isAuthorityRejection());
  }

  @Test
  void concurrentProofsOfTheSameCertificateAreCheckedOnce() throws Exception {
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(1, NOW));
    OperationUnicityCertificateVerifier verifier = strict();
    RootTrustBase trustBase = AUTHORITY.trustBase();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<VerificationStatus>> futures = new ArrayList<>();
      for (int i = 0; i < 64; i++) {
        String shard = new String[] {"00", "01", "10", "11"}[i % 4];
        futures.add(pool.submit(() -> verifier.verifyCertificate(trustBase,
            sealed.certificate(shard)).getStatus()));
      }
      for (Future<VerificationStatus> future : futures) {
        assertEquals(VerificationStatus.OK, future.get(30, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, verifier.getStats().getQuorumChecks());
    assertEquals(4, verifier.getStats().getBodyChecks());
  }

  @Test
  void roundsAndEpochsAbove2To63VerifyAsUnsignedNumbers() {
    TestChain.Authority high = new TestChain.Authority(NetworkId.LOCAL, -1L, Long.MIN_VALUE, 1);
    TestChain.Sealed sealed = TestChain.seal(high, 7, -1L, TestChain.fourShards(Long.MIN_VALUE, NOW));
    OperationUnicityCertificateVerifier verifier = strict();
    UnicityCertificateVerificationResult result =
        verifier.verifyCertificate(high.trustBase(), sealed.certificate("00"));
    assertEquals(VerificationStatus.OK, result.getStatus());
    assertEquals("18446744073709551615",
        sealed.certificate("00").getUnicitySeal().getRootChainRoundNumberDecimal());
    assertEquals("9223372036854775808",
        sealed.certificate("00").getInputRecord().getRoundNumberDecimal());
  }
}
