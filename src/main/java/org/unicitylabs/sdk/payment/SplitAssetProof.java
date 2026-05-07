package org.unicitylabs.sdk.payment;

import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.smt.plain.SparseMerkleTreePath;
import org.unicitylabs.sdk.smt.sum.SparseMerkleSumTreePath;

import java.util.List;
import java.util.Objects;

/**
 * Proof material for one split reason entry.
 */
public final class SplitAssetProof {
  private final AssetId assetId;
  private final SparseMerkleTreePath aggregationPath;
  private final SparseMerkleSumTreePath assetTreePath;

  private SplitAssetProof(
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
  public static SplitAssetProof create(
          AssetId assetId,
          SparseMerkleTreePath aggregationPath,
          SparseMerkleSumTreePath assetTreePath
  ) {
    return new SplitAssetProof(assetId, aggregationPath, assetTreePath);
  }

  /**
   * Deserialize split reason proof from CBOR bytes.
   *
   * @param bytes CBOR bytes
   *
   * @return split reason proof
   */
  public static SplitAssetProof fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes, 3);

    return new SplitAssetProof(
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

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SplitAssetProof)) return false;
    SplitAssetProof that = (SplitAssetProof) o;
    return Objects.equals(this.assetId, that.assetId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.assetId);
  }
}
