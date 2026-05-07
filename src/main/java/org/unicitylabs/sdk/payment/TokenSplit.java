package org.unicitylabs.sdk.payment;

import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.builtin.BurnPredicate;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.smt.BranchExistsException;
import org.unicitylabs.sdk.smt.LeafOutOfBoundsException;
import org.unicitylabs.sdk.smt.plain.SparseMerkleTree;
import org.unicitylabs.sdk.smt.plain.SparseMerkleTreeRootNode;
import org.unicitylabs.sdk.smt.sum.SparseMerkleSumTree;
import org.unicitylabs.sdk.smt.sum.SparseMerkleSumTreeRootNode;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TransferTransaction;

import java.security.SecureRandom;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Utilities for creating and verifying token split proofs.
 */
public class TokenSplit {

  private static final SecureRandom RANDOM = new SecureRandom();

  private TokenSplit() {
  }

  /**
   * Create split proofs and burn transaction for provided target token distributions.
   *
   * @param token source token being split
   * @param paymentDataDeserializer payment data decoder for source token payload
   * @param splitTokens destination token ids and their asset allocations
   *
   * @return split result containing burn transaction and proof map
   *
   * @throws LeafOutOfBoundsException if a leaf path is invalid for merkle tree insertion
   * @throws BranchExistsException if duplicate branches are inserted into a merkle tree
   */
  public static SplitResult split(
          Token token,
          PaymentDataDeserializer paymentDataDeserializer,
          Map<TokenId, Set<Asset>> splitTokens
  ) throws LeafOutOfBoundsException, BranchExistsException {
    Objects.requireNonNull(token, "Token cannot be null");
    Objects.requireNonNull(paymentDataDeserializer, "Payment data deserializer cannot be null");
    Objects.requireNonNull(splitTokens, "Split tokens cannot be null");
    byte[] paymentDataBytes = token.getGenesis().getData().orElse(null);
    if (paymentDataBytes == null) {
      throw new IllegalArgumentException("Token genesis data must be present");
    }

    HashMap<AssetId, SparseMerkleSumTree> trees = new HashMap<AssetId, SparseMerkleSumTree>();
    for (Entry<TokenId, Set<Asset>> entry : splitTokens.entrySet()) {
      Objects.requireNonNull(entry, "Split token entry cannot be null");
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

    PaymentData paymentData = paymentDataDeserializer.decode(paymentDataBytes);
    Map<AssetId, Asset> assets = paymentData.getAssets().stream()
            .collect(Collectors.toMap(
                            Asset::getId,
                            asset -> asset,
                            (a, b) -> {
                              throw new IllegalArgumentException(
                                      "Payment data contains multiple assets with the same id: " + a.getId());
                            }
                    )
            );

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
    byte[] stateMask = new byte[32];
    RANDOM.nextBytes(stateMask);

    TransferTransaction burnTransaction = TransferTransaction.create(
            token,
            burnPredicate,
            stateMask,
            CborSerializer.encodeNull()
    );

    HashMap<TokenId, List<SplitAssetProof>> proofs = new HashMap<TokenId, List<SplitAssetProof>>();
    for (Entry<TokenId, Set<Asset>> entry : splitTokens.entrySet()) {
      proofs.put(
              entry.getKey(),
              List.copyOf(
                      entry.getValue().stream().map(asset -> SplitAssetProof.create(
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

}
