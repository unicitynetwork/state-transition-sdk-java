package org.unicitylabs.sdk.payment;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.mtree.BranchExistsException;
import org.unicitylabs.sdk.mtree.LeafOutOfBoundsException;
import org.unicitylabs.sdk.mtree.MerkleTreePathVerificationResult;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTree;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTreeRootNode;
import org.unicitylabs.sdk.mtree.sum.SparseMerkleSumTree;
import org.unicitylabs.sdk.mtree.sum.SparseMerkleSumTreeRootNode;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.builtin.BurnPredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Address;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class TokenSplit {
  private static final SecureRandom RANDOM = new SecureRandom();


  public static SplitResult split(
      Token token,
      Predicate ownerPredicate,
      PaymentDataDeserializer paymentDataDeserializer,
      Map<TokenId, Set<Asset>> splitTokens
  ) throws LeafOutOfBoundsException, BranchExistsException {
    Objects.requireNonNull(token, "Token cannot be null");
    Objects.requireNonNull(ownerPredicate, "Owner predicate cannot be null");
    Objects.requireNonNull(paymentDataDeserializer, "Payment data deserializer cannot be null");
    Objects.requireNonNull(splitTokens, "Split tokens cannot be null");

    HashMap<AssetId, SparseMerkleSumTree> trees = new HashMap<AssetId, SparseMerkleSumTree>();
    for (Entry<TokenId, Set<Asset>> entry : splitTokens.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "Split token id cannot be null");
      for (Asset asset : entry.getValue()) {
        Objects.requireNonNull(asset, "Split token asset cannot be null");

        SparseMerkleSumTree tree = trees.computeIfAbsent(asset.getId(),
            v -> new SparseMerkleSumTree(HashAlgorithm.SHA256));
        tree.addLeaf(
            entry.getKey().toBitString().toBigInteger(),
            new SparseMerkleSumTree.LeafValue(asset.getId().getBytes(), asset.getValue())
        );
      }
    }

    PaymentData paymentData = paymentDataDeserializer.decode(token.getGenesis().getData());
    Map<AssetId, Asset> assets = paymentData.getAssets().stream().collect(Collectors.toMap(Asset::getId, asset -> asset));

    if (trees.size() != assets.size()) {
      throw new IllegalArgumentException("Token and split tokens asset counts differ.");
    }

    SparseMerkleTree aggregationTree = new SparseMerkleTree(HashAlgorithm.SHA256);
    HashMap<AssetId, SparseMerkleSumTreeRootNode> assetTreeRoots = new HashMap<AssetId, SparseMerkleSumTreeRootNode>();
    for (Entry<AssetId, SparseMerkleSumTree> entry : trees.entrySet()) {
      Asset tokenAsset = assets.get(entry.getKey());
      if (tokenAsset == null) {
        throw new IllegalArgumentException(String.format("Token did not contain asset %s.", entry.getKey()));
      }

      SparseMerkleSumTreeRootNode root = entry.getValue().calculateRoot();
      if (!root.getValue().equals(tokenAsset.getValue())) {
        throw new IllegalArgumentException(
            String.format(
                "Token contained %s %s assets, but tree has %s",
                tokenAsset.getValue(),
                tokenAsset.getId(),
                root.getValue()
            )
        );
      }

      assetTreeRoots.put(tokenAsset.getId(), root);
      aggregationTree.addLeaf(tokenAsset.getId().toBitString().toBigInteger(), root.getRootHash().getImprint());
    }

    SparseMerkleTreeRootNode aggregationRoot = aggregationTree.calculateRoot();
    BurnPredicate burnPredicate = BurnPredicate.create(aggregationRoot.getRootHash().getImprint());
    byte[] x = new byte[32];
    RANDOM.nextBytes(x);

    TransferTransaction burnTransaction = TransferTransaction.create(
        token,
        ownerPredicate,
        Address.fromPredicate(burnPredicate),
        x,
        CborSerializer.encodeNull()
    );

    HashMap<TokenId, List<SplitReasonProof>> proofs = new HashMap<TokenId, List<SplitReasonProof>>();
    for (Entry<TokenId, Set<Asset>> entry : splitTokens.entrySet()) {
      proofs.put(
          entry.getKey(),
          List.copyOf(
              entry.getValue().stream().map(asset -> SplitReasonProof.create(
                      asset.getId(),
                      aggregationRoot.getPath(asset.getId().toBitString().toBigInteger()),
                      assetTreeRoots.get(asset.getId()).getPath(entry.getKey().toBitString().toBigInteger())
                  )
              ).collect(Collectors.toList())
          )
      );
    }

    return new SplitResult(burnTransaction, proofs);
  }

  public static VerificationResult<VerificationStatus> verify(
      Token token,
      SplitPaymentDataDeserializer paymentDataDeserializer,
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier
  ) {
    Objects.requireNonNull(token, "Token cannot be null");
    Objects.requireNonNull(paymentDataDeserializer, "Payment data deserializer cannot be null");
    Objects.requireNonNull(trustBase, "Trust base cannot be null");
    Objects.requireNonNull(predicateVerifier, "Predicate verifier cannot be null");

    SplitPaymentData data = paymentDataDeserializer.decode(token.getGenesis().getData());

    if (data.getAssets() == null) {
      return new VerificationResult<>(
          "TokenSplitReasonVerificationRule",
          VerificationStatus.FAIL,
          "Assets data is missing."
      );
    }

    if (data.getReason() == null) {
      return new VerificationResult<>(
          "TokenSplitReasonVerificationRule",
          VerificationStatus.FAIL,
          "Reason is missing."
      );
    }

    VerificationResult<VerificationStatus> verificationResult = data.getReason().getToken().verify(trustBase, predicateVerifier);
    if (verificationResult.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>(
          "TokenSplitReasonVerificationRule",
          VerificationStatus.FAIL,
          "Burn token verification failed.",
          verificationResult
      );
    }

    if (data.getAssets().size() != data.getReason().getProofs().size()) {
      return new VerificationResult<>(
          "TokenSplitReasonVerificationRule",
          VerificationStatus.FAIL,
          "Total amount of assets differ in token and proofs."
      );
    }

    Map<AssetId, Asset> assets = data.getAssets().stream().collect(Collectors.toMap(Asset::getId, asset -> asset));
    Transaction burnTokenLastTransaction = data.getReason().getToken().getLatestTransaction();
    for (SplitReasonProof proof : data.getReason().getProofs()) {
      MerkleTreePathVerificationResult aggregationPathResult = proof.getAggregationPath().verify(proof.getAssetId().toBitString().toBigInteger());
      if (!aggregationPathResult.isSuccessful()) {
        return new VerificationResult<>(
            "TokenSplitReasonVerificationRule",
            VerificationStatus.FAIL,
            String.format("Aggregation path verification failed for asset: %s", proof.getAssetId())
        );
      }

      MerkleTreePathVerificationResult assetTreePathResult = proof.getAssetTreePath().verify(token.getId().toBitString().toBigInteger());
      if (!assetTreePathResult.isSuccessful()) {
        return new VerificationResult<>(
            "TokenSplitReasonVerificationRule",
            VerificationStatus.FAIL,
            String.format("Asset tree path verification failed for token:  %s", token.getId())
        );
      }

      if (!Arrays.equals(
          proof.getAssetTreePath().getRootHash().getImprint(),
          proof.getAggregationPath().getSteps().get(0).getData().orElse(null)
      )) {
        return new VerificationResult<>(
            "TokenSplitReasonVerificationRule",
            VerificationStatus.FAIL,
            "Asset tree root does not match aggregation path leaf."
        );
      }

      Asset asset = assets.get(proof.getAssetId());

      if (asset == null) {
        return new VerificationResult<>(
            "TokenSplitReasonVerificationRule",
            VerificationStatus.FAIL,
            String.format("Asset id %s not found in asset data.", proof.getAssetId())
        );
      }

      BigInteger amount = asset.getValue();



      if (!proof.getAssetTreePath().getSteps().get(0).getValue().equals(amount)) {
        return new VerificationResult<>(
            "TokenSplitReasonVerificationRule",
            VerificationStatus.FAIL,
            String.format("Asset amount for asset id %s does not match asset tree leaf.", proof.getAssetId())
        );
      }

      if (!burnTokenLastTransaction.getRecipient().equals(Address.fromPredicate(BurnPredicate.create(proof.getAggregationPath().getRootHash().getImprint())))) {
        return new VerificationResult<>(
            "TokenSplitReasonVerificationRule",
            VerificationStatus.FAIL,
            "Aggregation path root does not match burn predicate."
        );
      }
    }

    return new VerificationResult<>(
        "TokenSplitReasonVerificationRule",
        VerificationStatus.OK
    );
  }

}
