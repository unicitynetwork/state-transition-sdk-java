package org.unicitylabs.sdk.payment;

import java.util.List;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTreePath;
import org.unicitylabs.sdk.mtree.sum.SparseMerkleSumTreePath;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/**
 * Proof material for one split reason entry.
 */
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

  /**
   * Get asset id referenced by this proof.
   *
   * @return asset id
   */
  public AssetId getAssetId() {
    return this.assetId;
  }

  /**
   * Get sparse merkle path in the aggregation tree.
   *
   * @return aggregation path
   */
  public SparseMerkleTreePath getAggregationPath() {
    return this.aggregationPath;
  }

  /**
   * Get sparse merkle sum tree path for the asset tree.
   *
   * @return asset tree path
   */
  public SparseMerkleSumTreePath getAssetTreePath() {
    return this.assetTreePath;
  }

  /**
   * Create split reason proof.
   *
   * @param assetId asset id
   * @param aggregationPath aggregation path
   * @param assetTreePath asset tree path
   *
   * @return split reason proof
   */
  public static SplitReasonProof create(
      AssetId assetId,
      SparseMerkleTreePath aggregationPath,
      SparseMerkleSumTreePath assetTreePath
  ) {
    return new SplitReasonProof(assetId, aggregationPath, assetTreePath);
  }

  /**
   * Deserialize split reason proof from CBOR bytes.
   *
   * @param bytes CBOR bytes
   *
   * @return split reason proof
   */
  public static SplitReasonProof fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new SplitReasonProof(
        AssetId.fromCbor(data.get(0)),
        SparseMerkleTreePath.fromCbor(data.get(1)),
        SparseMerkleSumTreePath.fromCbor(data.get(2))
    );
  }

  /**
   * Serialize split reason proof to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        this.assetId.toCbor(),
        this.aggregationPath.toCbor(),
        this.assetTreePath.toCbor()
    );
  }
}
