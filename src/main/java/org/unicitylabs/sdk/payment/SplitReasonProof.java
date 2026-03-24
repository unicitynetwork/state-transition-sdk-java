package org.unicitylabs.sdk.payment;

import java.util.List;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTreePath;
import org.unicitylabs.sdk.mtree.sum.SparseMerkleSumTreePath;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

public class SplitReasonProof {
  private final AssetId assetId;
  private final SparseMerkleTreePath aggregationPath;
  private final SparseMerkleSumTreePath assetTreePath;

  private SplitReasonProof(
      AssetId assetId,
      SparseMerkleTreePath aggregationPath,
      SparseMerkleSumTreePath assetTreePath
  ) {
    this.assetId = assetId;
    this.aggregationPath = aggregationPath;
    this.assetTreePath = assetTreePath;
  }

  public AssetId getAssetId() {
    return this.assetId;
  }

  public SparseMerkleTreePath getAggregationPath() {
    return this.aggregationPath;
  }

  public SparseMerkleSumTreePath getAssetTreePath() {
    return this.assetTreePath;
  }

  public static SplitReasonProof create(AssetId assetId, SparseMerkleTreePath aggregationPath, SparseMerkleSumTreePath assetTreePath) {
    return new SplitReasonProof(assetId, aggregationPath, assetTreePath);
  }

  public static SplitReasonProof fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new SplitReasonProof(
        AssetId.fromCbor(data.get(0)),
        SparseMerkleTreePath.fromCbor(data.get(1)),
        SparseMerkleSumTreePath.fromCbor(data.get(2))
    );
  }

  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        this.assetId.toCbor(),
        this.aggregationPath.toCbor(),
        this.assetTreePath.toCbor()
    );
  }
}
