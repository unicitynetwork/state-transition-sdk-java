package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.bft.UnicityCertificate.BodyViolation;
import org.unicitylabs.sdk.api.bft.verification.OperationUnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateFailure;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerificationResult;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

class UnicityCertificateBodyTest {

  private static final TestChain.Authority AUTHORITY = new TestChain.Authority();

  private static UnicityCertificate certificate(PartitionViewLeaf leaf) {
    return TestChain.seal(AUTHORITY, 7, 5, List.of(leaf)).certificate("");
  }

  private static UnicityCertificate withRecord(InputRecord record, byte[] technical,
      ShardTreeCertificate shardPath, UnicityTreeCertificate partition) {
    UnicityCertificate base = certificate(TestChain.leaf("", 1, 1_755_000_000L, 1));
    return new UnicityCertificate(record, technical, base.getShardConfigurationHash(), shardPath,
        partition, base.getUnicitySeal());
  }

  @Test
  void aWellFormedCertificateHasNoViolation() {
    assertNull(certificate(TestChain.leaf("", 1, 1_755_000_000L, 1)).checkBody());
  }

  @Test
  void theViolatedRuleIsReturnedInOrder() {
    UnicityCertificate base = certificate(TestChain.leaf("", 1, 1_755_000_000L, 1));
    UnicityTreeCertificate partition7 = base.getUnicityTreeCertificate();
    ShardTreeCertificate path = base.getShardTreeCertificate();
    InputRecord record = base.getInputRecord();

    assertEquals(BodyViolation.PARTITION, withRecord(record, new byte[32], path,
        new UnicityTreeCertificate(8, List.of())).checkBody());
    // the fixed partition is judged before everything else, whatever else is wrong
    assertEquals(BodyViolation.PARTITION, withRecord(record, new byte[3], path,
        new UnicityTreeCertificate(8, List.of())).checkBody());

    InputRecord noSummary = new InputRecord(1, 1, null, new byte[32], null, 1, new byte[32], 0, null);
    assertEquals(BodyViolation.INPUT_RECORD,
        withRecord(noSummary, new byte[32], path, partition7).checkBody());

    InputRecord shortRoot = new InputRecord(1, 1, null, new byte[31], new byte[8], 1, new byte[32],
        0, null);
    assertEquals(BodyViolation.STATE_HASH_LENGTH,
        withRecord(shortRoot, new byte[32], path, partition7).checkBody());

    assertEquals(BodyViolation.TECHNICAL_RECORD_HASH_LENGTH,
        withRecord(record, new byte[31], path, partition7).checkBody());
    assertEquals(BodyViolation.TECHNICAL_RECORD_HASH_LENGTH,
        withRecord(record, null, path, partition7).checkBody());

    ShardTreeCertificate tooLong = new ShardTreeCertificate(ShardId.empty(),
        List.of(new byte[32]));
    assertEquals(BodyViolation.SHARD_PATH_LENGTH,
        withRecord(record, new byte[32], tooLong, partition7).checkBody());
    ShardTreeCertificate tooShort = new ShardTreeCertificate(ShardId.fromBitString("01"),
        List.of(new byte[32]));
    assertEquals(BodyViolation.SHARD_PATH_LENGTH,
        withRecord(record, new byte[32], tooShort, partition7).checkBody());
  }

  @Test
  void aNullStateRootMeansTheEmptyTreeAndIsAcceptedAsABody() {
    InputRecord empty = InputRecord.fromCbor(org.unicitylabs.sdk.util.HexConverter.decode(
        "d9985a8a010000f6f6f600f600f6"));
    UnicityCertificate base = certificate(TestChain.leaf("", 1, 1_755_000_000L, 1));
    UnicityCertificate emptyTree = new UnicityCertificate(empty, new byte[32],
        base.getShardConfigurationHash(), base.getShardTreeCertificate(),
        base.getUnicityTreeCertificate(), base.getUnicitySeal());
    assertNull(emptyTree.checkBody());
  }

  @Test
  void aMalformedShardPathFailsTheHashRuleInsteadOfThrowing() {
    UnicityCertificate base = certificate(TestChain.leaf("", 1, 1_755_000_000L, 1));
    ShardTreeCertificate wrong = new ShardTreeCertificate(ShardId.fromBitString("0110"),
        List.of(new byte[32]));
    UnicityCertificate malformed = new UnicityCertificate(base.getInputRecord(),
        base.getTechnicalRecordHash(), base.getShardConfigurationHash(), wrong,
        base.getUnicityTreeCertificate(), base.getUnicitySeal());

    // directly: the rule is a failed result, the primitive refuses the shape
    assertThrows(IllegalArgumentException.class, () -> UnicityCertificate
        .calculateShardTreeCertificateRootHash(malformed.getInputRecord(),
            malformed.getTechnicalRecordHash(), malformed.getShardConfigurationHash(), wrong));
    UnicityCertificateVerificationResult result = new UnicityCertificateVerifier()
        .verifyCertificate(AUTHORITY.trustBase(), malformed);
    assertEquals(VerificationStatus.FAIL, result.getStatus());
    assertEquals(UnicityCertificateFailure.SHARD_PATH_INVALID, result.getFailure());
  }

  @Test
  void aPartitionAboveTwoToThe31ParsesAndIsAnotherPartitionNotACrash() {
    long partition = 0x80000007L;
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, partition, 5,
        List.of(TestChain.leaf("", 1, 1_755_000_000L, 1)));
    UnicityCertificate certificate = sealed.certificate("");
    UnicityTreeCertificate tree = certificate.getUnicityTreeCertificate();

    assertEquals(-2147483641, tree.getPartitionIdentifier());
    assertEquals(0x80000007L, tree.getPartitionIdentifierUnsigned());

    // it survives the codec byte for byte
    UnicityCertificate decoded = UnicityCertificate.fromCbor(certificate.toCbor());
    assertEquals(0x80000007L, decoded.getUnicityTreeCertificate().getPartitionIdentifierUnsigned());
    assertArrayEquals(certificate.toCbor(), decoded.toCbor());

    // and every verifier refuses it as another partition
    assertEquals(BodyViolation.PARTITION, certificate.checkBody());
    UnicityCertificateVerificationResult legacy = new UnicityCertificateVerifier()
        .verifyCertificate(AUTHORITY.trustBase(), certificate);
    assertEquals(UnicityCertificateFailure.PARTITION_MISMATCH, legacy.getFailure());
    OperationUnicityCertificateVerifier strict = (OperationUnicityCertificateVerifier)
        new UnicityCertificateVerifier().beginOperation(VerificationPolicy.STRICT, null);
    assertEquals(UnicityCertificateFailure.PARTITION_MISMATCH,
        strict.verifyCertificate(AUTHORITY.trustBase(), certificate).getFailure());
  }

  @Test
  void aHashStepKeyAboveTwoToThe31IsAnUnsignedNumber() {
    UnicityTreeCertificate.HashStep step = TestChain.step(0xF0000000, 9);
    assertEquals(0xF0000000L, step.getKeyUnsigned());
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, 7, 5,
        List.of(TestChain.leaf("", 1, 1_755_000_000L, 1)), List.of(step));
    UnicityCertificate certificate = sealed.certificate("");
    UnicityCertificate decoded = UnicityCertificate.fromCbor(certificate.toCbor());
    assertEquals(0xF0000000L, decoded.getUnicityTreeCertificate().getSteps().get(0).getKeyUnsigned());
    assertEquals(VerificationStatus.OK, new UnicityCertificateVerifier()
        .verifyCertificate(AUTHORITY.trustBase(), decoded).getStatus());
  }

  @Test
  void theCertifiedEmptyTreeCertificateRoundTripsAndVerifiesStrictly() {
    InputRecord empty = InputRecord.fromCbor(org.unicitylabs.sdk.util.HexConverter.decode(
        "d9985a8a010000f6f6f600f600f6"));
    byte[] technical = new byte[32];
    byte[] configuration = new byte[32];
    UnicityCertificate unsigned = new UnicityCertificate(empty, technical, configuration,
        new ShardTreeCertificate(ShardId.empty(), List.of()),
        new UnicityTreeCertificate(7, List.of()), new UnicitySeal(AUTHORITY.network(), 1,
        AUTHORITY.epoch(), TestChain.TIMESTAMP, null, new byte[32], java.util.Set.of()));
    byte[] root = UnicityCertificate.calculateShardTreeCertificateRootHash(empty, technical,
        configuration, unsigned.getShardTreeCertificate()).getData();
    UnicitySeal seal = AUTHORITY.seal(7, TestChain.unicityRoot(7, root, List.of()));
    UnicityCertificate certificate = new UnicityCertificate(empty, technical, configuration,
        unsigned.getShardTreeCertificate(), unsigned.getUnicityTreeCertificate(), seal);

    byte[] bytes = certificate.toCbor();
    UnicityCertificate decoded = UnicityCertificate.fromCbor(bytes);
    assertArrayEquals(bytes, decoded.toCbor());
    assertNull(decoded.getInputRecord().getHash());
    assertNull(decoded.checkBody());
    OperationUnicityCertificateVerifier strict = (OperationUnicityCertificateVerifier)
        new UnicityCertificateVerifier().beginOperation(VerificationPolicy.STRICT, null);
    assertSame(null, strict.verifyCertificate(AUTHORITY.trustBase(), decoded).getFailure());
    assertEquals(VerificationStatus.OK,
        strict.verifyCertificate(AUTHORITY.trustBase(), decoded).getStatus());
  }
}
