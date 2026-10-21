package org.unicitylabs.sdk;

import org.unicitylabs.sdk.api.*;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.RootTrustBaseUtils;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.smt.radix.SparseMerkleTree;
import org.unicitylabs.sdk.smt.radix.SparseMerkleTreeRootNode;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

public class TestAggregatorClient implements AggregatorClient {
  private final RootTrustBase trustBase;
  private final PredicateVerifierService predicateVerifier;
  private final SparseMerkleTree sparseMerkleTree;
  private final HashMap<StateId, CertificationData> requests = new HashMap<>();
  private final HashMap<StateId, Long> referenceTimes = new HashMap<>();
  private final SigningService signingService;
  /**
   * Reference time of the current round. Every accepted request is its own round here, so a
   * proof served later is anchored to a certificate whose input record time is past the one
   * the leaf was built from, exactly as it is against a live aggregator.
   */
  private long referenceTime;

  private TestAggregatorClient(SparseMerkleTree smt, SigningService signingService,
      long referenceTime) {
    this.referenceTime = referenceTime;
    this.sparseMerkleTree = smt;
    this.signingService = signingService;
    this.trustBase = RootTrustBaseUtils.generateRootTrustBase(this.signingService.getPublicKey());
    this.predicateVerifier = PredicateVerifierService.create();
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
    return create(privateKey, System.currentTimeMillis() / 1000);
  }

  /**
   * Creates an aggregator with the given key whose first round has the given reference time, so
   * that two aggregators under one key can certify the same requests at different times.
   */
  public static TestAggregatorClient create(byte[] privateKey, long referenceTime) {
    return new TestAggregatorClient(
            new SparseMerkleTree(HashAlgorithm.SHA256),
            new SigningService(privateKey),
            referenceTime
    );
  }


  @Override
  public CompletableFuture<CertificationResponse> submitCertificationRequest(CertificationData certificationData) {
    try {
      StateId stateId = StateId.fromCertificationData(certificationData);

      VerificationResult<VerificationStatus> result = this.predicateVerifier.verify(
              certificationData.getLockScript(),
              this.referenceTime,
              certificationData.getSourceStateHash(),
              certificationData.getTransactionHash(),
              certificationData.getUnlockScript()
      );

      if (result.getStatus() != VerificationStatus.OK) {
        return CompletableFuture.completedFuture(CertificationResponse.create(CertificationStatus.SIGNATURE_VERIFICATION_FAILED));
      }

      if (certificationData.getExpiresAt().isPresent()
              && this.referenceTime >= certificationData.getExpiresAt().get()) {
        return CompletableFuture.completedFuture(
                CertificationResponse.create(CertificationStatus.REQUEST_EXPIRED));
      }

      if (!this.requests.containsKey(stateId)) {
        DataHash leafValue =
            LeafValue.calculate(certificationData.getTransactionHash(), this.referenceTime);
        this.sparseMerkleTree.addLeaf(stateId.getData(), leafValue.getData());
        this.requests.put(stateId, certificationData);
        this.referenceTimes.put(stateId, this.referenceTime);
        this.referenceTime += 1;
      }

      return CompletableFuture.completedFuture(CertificationResponse.create(CertificationStatus.SUCCESS));
    } catch (Exception e) {
      throw new RuntimeException("Aggregator commitment failed", e);
    }
  }

  @Override
  public CompletableFuture<InclusionProofResponse> getInclusionProof(StateId stateId) {
    SparseMerkleTreeRootNode root = this.sparseMerkleTree.calculateRoot();

    if (!requests.containsKey(stateId)) {
      return CompletableFuture.completedFuture(InclusionProofFixture.createPendingResponse(
              root.getHash(), this.signingService, this.referenceTime));
    }

    CertificationData certificationData = requests.get(stateId);

    return CompletableFuture.completedFuture(
            InclusionProofFixture.createResponse(
                    certificationData,
                    this.referenceTimes.get(stateId),
                    InclusionCertificate.create(root, stateId.getData()),
                    root.getHash(),
                    this.signingService,
                    this.referenceTime
            )
    );
  }

  @Override
  public CompletableFuture<Long> getLatestBlockNumber() {
    return CompletableFuture.completedFuture(1L);
  }
}
