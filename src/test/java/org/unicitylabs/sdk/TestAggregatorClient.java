package org.unicitylabs.sdk;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.InclusionProofResponse;
import org.unicitylabs.sdk.api.InclusionProofFixture;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.RootTrustBaseUtils;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTree;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTreeRootNode;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

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

      var result = this.predicateVerifier.verify(
          certificationData.getLockScript(),
          certificationData.getSourceStateHash(),
          certificationData.getTransactionHash(),
          certificationData.getUnlockScript()
      );

      if (result.getStatus() != VerificationStatus.OK) {
        return CompletableFuture.completedFuture(CertificationResponse.create(CertificationStatus.SIGNATURE_VERIFICATION_FAILED));
      }

      if (!this.requests.containsKey(stateId)) {
        var leafValue = certificationData.calculateLeafValue();
        this.sparseMerkleTree.addLeaf(stateId.toBitString().toBigInteger(), leafValue.getImprint());
        this.requests.put(stateId, certificationData);
      }

      return CompletableFuture.completedFuture(CertificationResponse.create(CertificationStatus.SUCCESS));
    } catch (Exception e) {
      throw new RuntimeException("Aggregator commitment failed", e);
    }
  }

  @Override
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
    CertificationData certificationData = requests.get(stateId);
    SparseMerkleTreeRootNode root = this.sparseMerkleTree.calculateRoot();
      return CompletableFuture.completedFuture(
        InclusionProofFixture.create(
            root.getPath(stateId.toBitString().toBigInteger()),
            certificationData,
            root.getRootHash(),
            this.signingService
        )
    );
  }

  @Override
  public CompletableFuture<Long> getBlockHeight() {
    return CompletableFuture.completedFuture(1L);
  }
}
