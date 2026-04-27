package org.unicitylabs.sdk.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.RootTrustBaseUtils;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.UnicityCertificateUtils;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.smt.radix.FinalizedNodeBranch;
import org.unicitylabs.sdk.smt.radix.SparseMerkleTree;
import org.unicitylabs.sdk.transaction.Address;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationRule;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationStatus;
import org.unicitylabs.sdk.util.HexConverter;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class InclusionProofTest {

  MintTransaction transaction;
  PredicateVerifierService predicateVerifier;
  StateId stateId;
  InclusionCertificate inclusionCertificate;
  CertificationData certificationData;
  RootTrustBase trustBase;
  UnicityCertificate unicityCertificate;

  @BeforeAll
  public void createMerkleTreePath() throws Exception {
    SigningService signingService = new SigningService(
            HexConverter.decode("0000000000000000000000000000000000000000000000000000000000000001"));


    transaction = MintTransaction.create(
            Address.fromPredicate(PayToPublicKeyPredicate.fromSigningService(signingService)),
            TokenId.generate(),
            TokenType.generate(),
            new byte[32]
    );

    certificationData = CertificationData.fromMintTransaction(transaction);
    stateId = StateId.fromCertificationData(certificationData);

    SparseMerkleTree smt = new SparseMerkleTree(HashAlgorithm.SHA256);
    smt.addLeaf(stateId.getData(), certificationData.getTransactionHash().getData());

    FinalizedNodeBranch root = smt.calculateRoot();
    inclusionCertificate = InclusionCertificate.create(root, stateId.getData());
    SigningService ucSigningService = new SigningService(SigningService.generatePrivateKey());
    trustBase = RootTrustBaseUtils.generateRootTrustBase(ucSigningService.getPublicKey());
    unicityCertificate = UnicityCertificateUtils.generateCertificate(ucSigningService, root.getHash());
    predicateVerifier = PredicateVerifierService.create(trustBase);
  }

  @Test
  public void testCborSerialization() {
    InclusionProof inclusionProof = new InclusionProof(
            certificationData,
            inclusionCertificate,
            unicityCertificate
    );

    Assertions.assertEquals(inclusionProof, InclusionProof.fromCbor(inclusionProof.toCbor()));
  }

  @Test
  public void testStructure() {
    Assertions.assertThrows(NullPointerException.class,
            () -> new InclusionProof(
                    this.certificationData,
                    this.inclusionCertificate,
                    null
            )
    );
    Assertions.assertInstanceOf(InclusionProof.class,
            new InclusionProof(
                    this.certificationData,
                    this.inclusionCertificate,
                    this.unicityCertificate
            )
    );
    Assertions.assertInstanceOf(InclusionProof.class,
            new InclusionProof(
                    null,
                    this.inclusionCertificate,
                    this.unicityCertificate
            )
    );
  }

  @Test
  public void testItVerifies() {
    InclusionProof inclusionProof = new InclusionProof(
            this.certificationData,
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
                    this.certificationData.getUnlockScript()
            ),
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
                    PayToPublicKeyPredicateUnlockScript.create(
                            this.transaction,
                            new SigningService(SigningService.generatePrivateKey())
                    ).encode()
            ),
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
  public void testVerificationFailsWithInvalidTrustbase() {
    InclusionProof inclusionProof = new InclusionProof(
            this.certificationData,
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
