package org.unicitylabs.sdk;

import org.unicitylabs.sdk.api.*;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.RootTrustBaseUtils;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.smt.radix.FinalizedNodeBranch;
import org.unicitylabs.sdk.smt.radix.SparseMerkleTree;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

public class TestAggregatorClient implements AggregatorClient {
    private final RootTrustBase trustBase;
    private final PredicateVerifierService predicateVerifier;
    private final SparseMerkleTree sparseMerkleTree;
    private final HashMap<StateId, CertificationData> requests = new HashMap<>();
    private final SigningService signingService;

    private TestAggregatorClient(SparseMerkleTree smt, SigningService signingService) {
        this.sparseMerkleTree = smt;
        this.signingService = signingService;
        this.trustBase = RootTrustBaseUtils.generateRootTrustBase(this.signingService.getPublicKey());
        this.predicateVerifier = PredicateVerifierService.create(this.trustBase);
    }

    public RootTrustBase getTrustBase() {
        return this.trustBase;
    }

    /**
     * Creates a new TestAggregatorClient instance with generated private key. If no private key is provided, a new one is
     * generated.
     */
    public static TestAggregatorClient create() {
        return TestAggregatorClient.create(SigningService.generatePrivateKey());
    }


    /**
     * Creates a new TestAggregatorClient instance with private key. If no private key is provided, a new one is
     * generated.
     */
    public static TestAggregatorClient create(byte[] privateKey) {
        return new TestAggregatorClient(
                new SparseMerkleTree(HashAlgorithm.SHA256),
                new SigningService(privateKey)
        );
    }


    @Override
    public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData certificationData) {
        try {
            StateId stateId = StateId.fromCertificationData(certificationData);

            VerificationResult<VerificationStatus> result = this.predicateVerifier.verify(
                    certificationData.getLockScript(),
                    certificationData.getSourceStateHash(),
                    certificationData.getTransactionHash(),
                    certificationData.getUnlockScript()
            );

            if (result.getStatus() != VerificationStatus.OK) {
                return CompletableFuture.completedFuture(CertificationResponse.create(CertificationStatus.SIGNATURE_VERIFICATION_FAILED));
            }

            if (!this.requests.containsKey(stateId)) {
                DataHash leafValue = certificationData.getTransactionHash();
                this.sparseMerkleTree.addLeaf(stateId.getData(), leafValue.getData());
                this.requests.put(stateId, certificationData);
            }

            return CompletableFuture.completedFuture(CertificationResponse.create(CertificationStatus.SUCCESS));
        } catch (Exception e) {
            throw new RuntimeException("Aggregator commitment failed", e);
        }
    }

    @Override
    public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
        FinalizedNodeBranch root = this.sparseMerkleTree.calculateRoot();

        if (!requests.containsKey(stateId)) {
          return CompletableFuture.completedFuture(InclusionProofFixture.createResponse(null, null, root.getHash(), this.signingService));
        }

        CertificationData certificationData = requests.get(stateId);

        return CompletableFuture.completedFuture(
                InclusionProofFixture.createResponse(
                        certificationData,
                        InclusionCertificate.create(root, stateId.getData()),
                        root.getHash(),
                        this.signingService
                )
        );
    }

    @Override
    public CompletableFuture<Long> getBlockHeight() {
        return CompletableFuture.completedFuture(1L);
    }
}
