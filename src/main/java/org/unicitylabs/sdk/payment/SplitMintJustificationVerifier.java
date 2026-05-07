package org.unicitylabs.sdk.payment;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.builtin.BurnPredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.smt.MerkleTreePathVerificationResult;
import org.unicitylabs.sdk.transaction.CertifiedMintTransaction;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifier;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SplitMintJustificationVerifier implements MintJustificationVerifier {
  private final RootTrustBase trustBase;
  private final PredicateVerifierService predicateVerifier;
  private final PaymentDataDeserializer decodePaymentData;

  public SplitMintJustificationVerifier(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          PaymentDataDeserializer decodePaymentData
  ) {
    this.trustBase = Objects.requireNonNull(trustBase, "trustBase cannot be null");
    this.predicateVerifier = Objects.requireNonNull(predicateVerifier, "predicateVerifier cannot be null");
    this.decodePaymentData = Objects.requireNonNull(decodePaymentData, "decodePaymentData cannot be null");
  }

  @Override
  public long getTag() {
    return SplitMintJustification.CBOR_TAG;
  }

  @Override
  public VerificationResult<VerificationStatus> verify(CertifiedMintTransaction transaction, MintJustificationVerifierService mintJustificationVerifier) {
    Objects.requireNonNull(transaction, "transaction cannot be null");
    Objects.requireNonNull(mintJustificationVerifier, "mintJustificationVerifierService cannot be null");

    byte[] justificationBytes = transaction.getJustification().orElse(null);
    if (justificationBytes == null) {
      return new VerificationResult<>(
              "SplitMintJustificationVerificationRule",
              VerificationStatus.FAIL,
              "Transaction has no justification."
      );
    }

    SplitMintJustification justification = SplitMintJustification.fromCbor(justificationBytes);
    byte[] paymentDataBytes = transaction.getData().orElse(null);
    PaymentData paymentData = paymentDataBytes != null ? this.decodePaymentData.decode(paymentDataBytes) : null;

    if (paymentData == null || paymentData.getAssets() == null) {
      return new VerificationResult<>(
              "SplitMintJustificationVerificationRule",
              VerificationStatus.FAIL,
              "Assets data is missing."
      );
    }

    VerificationResult<VerificationStatus> verificationResult = justification.getToken()
            .verify(trustBase, predicateVerifier, mintJustificationVerifier);
    if (verificationResult.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>(
              "SplitMintJustificationVerificationRule",
              VerificationStatus.FAIL,
              "Burn token verification failed.",
              verificationResult
      );
    }

    Map<AssetId, Asset> assets = new HashMap<>();
    for (Asset asset : paymentData.getAssets()) {
      if (asset == null) {
        return new VerificationResult<>(
                "SplitMintJustificationVerificationRule",
                VerificationStatus.FAIL,
                "Asset data is missing."
        );
      }

      AssetId assetId = asset.getId();
      if (assets.putIfAbsent(assetId, asset) != null) {
        return new VerificationResult<>(
                "SplitMintJustificationVerificationRule",
                VerificationStatus.FAIL,
                String.format("Duplicate asset id %s found in asset data.", assetId)
        );
      }
    }

    if (assets.size() != justification.getProofs().size()) {
      return new VerificationResult<>(
              "SplitMintJustificationVerificationRule",
              VerificationStatus.FAIL,
              "Total amount of assets differ in token and proofs."
      );
    }

    Set<AssetId> validatedAssets = new HashSet<>();
    Transaction burnTokenLastTransaction = justification.getToken().getLatestTransaction();
    DataHash root = justification.getProofs().get(0).getAggregationPath().getRootHash();
    for (SplitAssetProof proof : justification.getProofs()) {
      if (!validatedAssets.add(proof.getAssetId())) {
        return new VerificationResult<>(
                "SplitMintJustificationVerificationRule",
                VerificationStatus.FAIL,
                String.format("Duplicate split proof for asset id %s.", proof.getAssetId())
        );
      }

      MerkleTreePathVerificationResult aggregationPathResult = proof.getAggregationPath()
              .verify(proof.getAssetId().toBitString().toBigInteger());
      if (!aggregationPathResult.isSuccessful()) {
        return new VerificationResult<>(
                "SplitMintJustificationVerificationRule",
                VerificationStatus.FAIL,
                String.format("Aggregation path verification failed for asset: %s", proof.getAssetId())
        );
      }

      MerkleTreePathVerificationResult assetTreePathResult = proof.getAssetTreePath()
              .verify(transaction.getTokenId().toBitString().toBigInteger());
      if (!assetTreePathResult.isSuccessful()) {
        return new VerificationResult<>(
                "SplitMintJustificationVerificationRule",
                VerificationStatus.FAIL,
                String.format("Asset tree path verification failed for token:  %s", transaction.getTokenId())
        );
      }

      if (!proof.getAggregationPath().getRootHash().equals(root)) {
        return new VerificationResult<>(
                "SplitMintJustificationVerificationRule",
                VerificationStatus.FAIL,
                "Current proof is not derived from the same asset tree as other proofs."
        );
      }

      if (!Arrays.equals(
              proof.getAssetTreePath().getRootHash().getImprint(),
              proof.getAggregationPath().getSteps().get(0).getData().orElse(null)
      )) {
        return new VerificationResult<>(
                "SplitMintJustificationVerificationRule",
                VerificationStatus.FAIL,
                "Asset tree root does not match aggregation path leaf."
        );
      }

      Asset asset = assets.get(proof.getAssetId());

      if (asset == null) {
        return new VerificationResult<>(
                "SplitMintJustificationVerificationRule",
                VerificationStatus.FAIL,
                String.format("Asset id %s not found in asset data.", proof.getAssetId())
        );
      }

      BigInteger amount = asset.getValue();

      if (!proof.getAssetTreePath().getSteps().get(0).getValue().equals(amount)) {
        return new VerificationResult<>(
                "SplitMintJustificationVerificationRule",
                VerificationStatus.FAIL,
                String.format("Asset amount for asset id %s does not match asset tree leaf.", proof.getAssetId())
        );
      }

      EncodedPredicate expectedRecipient = EncodedPredicate.fromPredicate(
              BurnPredicate.create(proof.getAggregationPath().getRootHash().getImprint())
      );

      if (!expectedRecipient.equals(burnTokenLastTransaction.getRecipient())) {
        return new VerificationResult<>(
                "SplitMintJustificationVerificationRule",
                VerificationStatus.FAIL,
                "Aggregation path root does not match burn predicate."
        );
      }
    }

    if (validatedAssets.size() != assets.size()) {
      return new VerificationResult<>(
              "SplitMintJustificationVerificationRule",
              VerificationStatus.FAIL,
              "Some assets proofs are missing from the token."
      );
    }

    return new VerificationResult<>(
            "SplitMintJustificationVerificationRule",
            VerificationStatus.OK
    );
  }
}
