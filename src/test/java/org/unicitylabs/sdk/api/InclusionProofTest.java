package org.unicitylabs.sdk.api;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.RootTrustBaseUtils;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.api.bft.UnicityCertificateUtils;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.smt.radix.SparseMerkleTree;
import org.unicitylabs.sdk.smt.radix.SparseMerkleTreeRootNode;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationRule;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationStatus;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.utils.ExpiresAt;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class InclusionProofTest {

  static final long REFERENCE_TIME = 1755000000L;

  MintTransaction transaction;
  PredicateVerifierService predicateVerifier;
  StateId stateId;
  InclusionCertificate inclusionCertificate;
  CertificationData certificationData;
  RootTrustBase trustBase;
  UnicityCertificate unicityCertificate;
  DataHash rootHash;

  @BeforeAll
  public void createMerkleTreePath() throws Exception {
    SigningService signingService = new SigningService(
            HexConverter.decode("0000000000000000000000000000000000000000000000000000000000000001"));


    transaction = MintTransaction.builder(NetworkId.LOCAL, SignaturePredicate.fromSigningService(signingService))
            .expiresAt(ExpiresAt.expiresAt())
            .build();

    certificationData = CertificationData.fromMintTransaction(transaction);
    stateId = StateId.fromCertificationData(certificationData);

    SparseMerkleTree smt = new SparseMerkleTree(HashAlgorithm.SHA256);
    smt.addLeaf(stateId.getData(),
            LeafValue.calculate(certificationData.getTransactionHash(), REFERENCE_TIME).getData());

    SparseMerkleTreeRootNode root = smt.calculateRoot();
    rootHash = root.getHash();
    inclusionCertificate = InclusionCertificate.create(root, stateId.getData());
    // Reuse user signing service as unicity certificate signing service.
    trustBase = RootTrustBaseUtils.generateRootTrustBase(signingService.getPublicKey());
    unicityCertificate = UnicityCertificateUtils.generateCertificate(signingService, root.getHash());
    predicateVerifier = PredicateVerifierService.create();
  }

  @Test
  public void testCborSerialization() {
    InclusionProof inclusionProof = new InclusionProof(
            certificationData,
            REFERENCE_TIME,
            inclusionCertificate,
            unicityCertificate
    );

    Assertions.assertEquals(inclusionProof, InclusionProof.fromCbor(inclusionProof.toCbor()));
  }

  /**
   * A proof either establishes a leaf or reports that there is none yet. The aggregators emit all
   * three leaf fields together or none of them, so a partially present proof is a protocol
   * violation and is rejected at decode rather than surfacing as an empty Optional downstream.
   */
  @Test
  public void rejectsAPartiallyPresentProof() {
    byte[] complete = new InclusionProof(certificationData, REFERENCE_TIME, inclusionCertificate,
            unicityCertificate).toCbor();
    List<byte[]> fields = CborDeserializer.decodeArray(
            CborDeserializer.decodeTag(complete).getData(), 5);
    byte[] nul = CborSerializer.encodeNull();

    byte[][][] partial = {
        {fields.get(1), fields.get(2), nul},
        {fields.get(1), nul, fields.get(3)},
        {nul, fields.get(2), fields.get(3)},
        {nul, nul, fields.get(3)},
    };

    for (byte[][] combination : partial) {
      byte[] encoded = CborSerializer.encodeTag(
              InclusionProof.CBOR_TAG,
              CborSerializer.encodeArray(fields.get(0), combination[0], combination[1],
                      combination[2], fields.get(4)));

      Assertions.assertThrows(
              CborSerializationException.class,
              () -> InclusionProof.fromCbor(encoded));
    }
  }

  // The wire form also expresses "no leaf yet". That is not an InclusionProof — the response is
  // the type that carries it, and asking for a proof anyway is an error rather than a null.
  @Test
  public void decodesTheAbsentFormAsAnAbsence() {
    byte[] encoded = CborDeserializer.decodeArray(
            InclusionProofResponse.notCertified(1L, unicityCertificate).toCbor(), 2).get(1);

    Assertions.assertThrows(CborSerializationException.class, () -> InclusionProof.fromCbor(encoded));
  }

  @Test
  public void testStructure() {
    Assertions.assertThrows(NullPointerException.class,
            () -> new InclusionProof(
                    this.certificationData,
                    REFERENCE_TIME,
                    this.inclusionCertificate,
                    null
            )
    );
    Assertions.assertInstanceOf(InclusionProof.class,
            new InclusionProof(
                    this.certificationData,
                    REFERENCE_TIME,
                    this.inclusionCertificate,
                    this.unicityCertificate
            )
    );
  }

  @Test
  public void testItVerifies() {
    InclusionProof inclusionProof = new InclusionProof(
            this.certificationData,
            REFERENCE_TIME,
            this.inclusionCertificate,
            this.unicityCertificate
    );
    Assertions.assertEquals(
            InclusionProofVerificationStatus.OK,
            InclusionProofVerificationRule.verify(
                    this.trustBase,
                    this.predicateVerifier,
                    inclusionProof,
                    this.transaction
            ).getStatus()
    );

    InclusionProof invalidTransactionHashInclusionProof = new InclusionProof(
            new CertificationData(
                    this.certificationData.getLockScript(),
                    this.certificationData.getSourceStateHash(),
                    DataHash.fromImprint(
                            HexConverter.decode("00000000000000000000000000000000000000000000000000000000000000000001")
                    ),
                    this.certificationData.getExpiresAt().orElse(null),
                    this.certificationData.getUnlockScript()
            ),
            REFERENCE_TIME,
            this.inclusionCertificate,
            this.unicityCertificate
    );

    Assertions.assertEquals(
            InclusionProofVerificationStatus.TRANSACTION_HASH_MISMATCH,
            InclusionProofVerificationRule.verify(
                    this.trustBase,
                    this.predicateVerifier,
                    invalidTransactionHashInclusionProof,
                    this.transaction
            ).getStatus()
    );
  }

  @Test
  public void testItNotAuthenticated() {
    InclusionProof invalidInclusionProof = new InclusionProof(
            new CertificationData(
                    this.certificationData.getLockScript(),
                    this.certificationData.getSourceStateHash(),
                    this.certificationData.getTransactionHash(),
                    this.certificationData.getExpiresAt().orElse(null),
                    SignaturePredicateUnlockScript.create(
                            this.transaction,
                            new SigningService(SigningService.generatePrivateKey())
                    ).encode()
            ),
            REFERENCE_TIME,
            this.inclusionCertificate,
            this.unicityCertificate
    );

    Assertions.assertEquals(
            InclusionProofVerificationStatus.NOT_AUTHENTICATED,
            InclusionProofVerificationRule.verify(
                    this.trustBase,
                    this.predicateVerifier,
                    invalidInclusionProof,
                    this.transaction
            ).getStatus()
    );
  }

  @Test
  public void testHighSUnlockSignatureIsNotAuthenticated() {
    // the mint transaction is unlocked by the mint signing service
    org.unicitylabs.sdk.crypto.secp256k1.Signature low =
            SignaturePredicateUnlockScript.decode(this.certificationData.getUnlockScript()).getSignature();
    BigInteger n = new BigInteger(
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16);
    byte[] lowBytes = low.getBytes();

    // the unmodified low-S unlock script authenticates
    Assertions.assertEquals(InclusionProofVerificationStatus.OK,
            this.verifyWithUnlockScript(low.encode()));

    // the high-S twin, with the matching and with the flipped recovery byte, does not
    byte[] highBytes = new byte[64];
    System.arraycopy(lowBytes, 0, highBytes, 0, 32);
    byte[] highS = n.subtract(new BigInteger(1, Arrays.copyOfRange(lowBytes, 32, 64))).toByteArray();
    System.arraycopy(highS, Math.max(0, highS.length - 32), highBytes,
            64 - Math.min(32, highS.length), Math.min(32, highS.length));
    for (int recovery : new int[] {low.getRecovery(), low.getRecovery() ^ 1}) {
      Assertions.assertEquals(InclusionProofVerificationStatus.NOT_AUTHENTICATED,
              this.verifyWithUnlockScript(withRecovery(highBytes, recovery)));
    }
  }

  private static byte[] withRecovery(byte[] compact, int recovery) {
    byte[] encoded = Arrays.copyOf(compact, 65);
    encoded[64] = (byte) recovery;
    return encoded;
  }

  private InclusionProofVerificationStatus verifyWithUnlockScript(byte[] unlockScript) {
    return InclusionProofVerificationRule.verify(
            this.trustBase,
            this.predicateVerifier,
            new InclusionProof(
                    new CertificationData(
                            this.certificationData.getLockScript(),
                            this.certificationData.getSourceStateHash(),
                            this.certificationData.getTransactionHash(),
                            this.certificationData.getExpiresAt().orElse(null),
                            unlockScript
                    ),
                    REFERENCE_TIME,
                    this.inclusionCertificate,
                    this.unicityCertificate
            ),
            this.transaction
    ).getStatus();
  }

  @Test
  public void testItFailsWithShardIdMismatch() {
    // 1-byte shard id whose first byte doesn't match the state id's first byte. The shard check
    // runs before the trust base check, so the signing service used for the new certificate's seal
    // is irrelevant — reuse the test's fixed key.
    byte mismatchingByte = (byte) (this.stateId.getData()[0] ^ 0xFF);
    ShardId mismatchingShardId = ShardId.decode(new byte[]{mismatchingByte, (byte) 0x80});
    DataHash rootHash = new DataHash(HashAlgorithm.SHA256,
            this.unicityCertificate.getInputRecord().getHash());
    SigningService signingService = SigningService.generate();
    UnicityCertificate mismatchingCertificate = UnicityCertificateUtils.generateCertificate(
            signingService,
            rootHash,
            mismatchingShardId
    );

    InclusionProof inclusionProof = new InclusionProof(
            this.certificationData,
            REFERENCE_TIME,
            this.inclusionCertificate,
            mismatchingCertificate
    );

    Assertions.assertEquals(
            InclusionProofVerificationStatus.SHARD_ID_MISMATCH,
            InclusionProofVerificationRule.verify(
                    RootTrustBaseUtils.generateRootTrustBase(signingService.getPublicKey()),
                    this.predicateVerifier,
                    inclusionProof,
                    this.transaction
            ).getStatus()
    );
  }

  @Test
  public void testVerificationFailsWhenReferenceTimeReachesTheTimeout() throws Exception {
    // A leaf whose deadline the round it was created in had already reached. The deadline is
    // exclusive, so equality is already too late.
    SigningService signingService = new SigningService(
            HexConverter.decode("0000000000000000000000000000000000000000000000000000000000000001"));
    MintTransaction expired = MintTransaction.builder(
                    NetworkId.LOCAL, SignaturePredicate.fromSigningService(signingService))
            .salt(this.transaction.getSalt())
            .tokenType(this.transaction.getTokenType())
            .expiresAt(REFERENCE_TIME)
            .build();
    CertificationData expiredData = CertificationData.fromMintTransaction(expired);
    StateId expiredStateId = StateId.fromCertificationData(expiredData);

    SparseMerkleTree smt = new SparseMerkleTree(HashAlgorithm.SHA256);
    smt.addLeaf(expiredStateId.getData(),
            LeafValue.calculate(expiredData.getTransactionHash(), REFERENCE_TIME).getData());
    SparseMerkleTreeRootNode root = smt.calculateRoot();

    InclusionProof inclusionProof = new InclusionProof(
            expiredData,
            REFERENCE_TIME,
            InclusionCertificate.create(root, expiredStateId.getData()),
            UnicityCertificateUtils.generateCertificate(signingService, root.getHash())
    );

    Assertions.assertEquals(
            InclusionProofVerificationStatus.REQUEST_EXPIRED,
            InclusionProofVerificationRule.verify(
                    this.trustBase,
                    this.predicateVerifier,
                    inclusionProof,
                    expired
            ).getStatus()
    );
  }

  // A leaf cannot postdate the round that certified it, and consensus signs that round's
  // timestamp, so a leaf claiming to be newer than its own round is an impossible pairing.
  @Test
  public void testVerificationFailsWhenLeafPostdatesItsCertifyingRound() {
    SigningService signingService = new SigningService(
            HexConverter.decode("0000000000000000000000000000000000000000000000000000000000000001"));
    InclusionProof inclusionProof = new InclusionProof(
            this.certificationData,
            REFERENCE_TIME,
            this.inclusionCertificate,
            UnicityCertificateUtils.generateCertificate(
                    signingService, this.rootHash, REFERENCE_TIME - 1)
    );

    Assertions.assertEquals(
            InclusionProofVerificationStatus.REFERENCE_TIME_AFTER_ROUND,
            InclusionProofVerificationRule.verify(
                    this.trustBase,
                    this.predicateVerifier,
                    inclusionProof,
                    this.transaction
            ).getStatus()
    );
  }

  @Test
  public void testAcceptsALeafBackDatedByADishonestService() throws Exception {
    long deadline = REFERENCE_TIME;
    long backDated = deadline - 1;
    SigningService signingService = new SigningService(
            HexConverter.decode("0000000000000000000000000000000000000000000000000000000000000001"));
    MintTransaction late = MintTransaction.builder(
                    NetworkId.LOCAL, SignaturePredicate.fromSigningService(signingService))
            .salt(this.transaction.getSalt())
            .tokenType(this.transaction.getTokenType())
            .expiresAt(deadline)
            .build();
    CertificationData lateData = CertificationData.fromMintTransaction(late);
    StateId lateStateId = StateId.fromCertificationData(lateData);

    // Built now, but claiming to have been created before the deadline.
    SparseMerkleTree smt = new SparseMerkleTree(HashAlgorithm.SHA256);
    smt.addLeaf(lateStateId.getData(),
            LeafValue.calculate(lateData.getTransactionHash(), backDated).getData());
    SparseMerkleTreeRootNode root = smt.calculateRoot();

    Assertions.assertEquals(
            InclusionProofVerificationStatus.OK,
            InclusionProofVerificationRule.verify(this.trustBase, this.predicateVerifier,
                    new InclusionProof(
                            lateData,
                            backDated,
                            InclusionCertificate.create(root, lateStateId.getData()),
                            // A round certified long after the deadline had passed.
                            UnicityCertificateUtils.generateCertificate(
                                    signingService, root.getHash(), deadline + 4000)),
                    late).getStatus()
    );
  }

  @Test
  public void testVerificationFailsWithInvalidTrustbase() {
    InclusionProof inclusionProof = new InclusionProof(
            this.certificationData,
            REFERENCE_TIME,
            this.inclusionCertificate,
            this.unicityCertificate
    );

    Assertions.assertEquals(
            InclusionProofVerificationStatus.INVALID_TRUSTBASE,
            InclusionProofVerificationRule.verify(
                    RootTrustBaseUtils.generateRootTrustBase(
                            HexConverter.decode("020000000000000000000000000000000000000000000000000000000000000001")
                    ),
                    this.predicateVerifier,
                    inclusionProof,
                    this.transaction
            ).getStatus()
    );
  }
}
