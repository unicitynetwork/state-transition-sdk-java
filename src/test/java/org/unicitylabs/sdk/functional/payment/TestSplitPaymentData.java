package org.unicitylabs.sdk.functional.payment;

import org.unicitylabs.sdk.payment.SplitPaymentData;
import org.unicitylabs.sdk.payment.SplitReason;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Test implementation of split payment payload used by functional tests.
 */
public class TestSplitPaymentData implements SplitPaymentData {

  private final Set<Asset> assets;
  private final SplitReason reason;

  /**
   * Create test split payment data.
   *
   * @param assets split assets
   * @param reason split reason with proofs
   */
  public TestSplitPaymentData(Set<Asset> assets, SplitReason reason) {
    this.assets = assets;
    this.reason = reason;
  }

  /**
   * Get split assets.
   *
   * @return split assets
   */
  public Set<Asset> getAssets() {
    return this.assets;
  }

  /**
   * Get split reason.
   *
   * @return split reason
   */
  @Override
  public SplitReason getReason() {
    return this.reason;
  }

  /**
   * Decode split payment data from CBOR bytes.
   *
   * @param bytes encoded split payment data
   *
   * @return decoded split payment data
   */
  public static TestSplitPaymentData decode(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    Set<Asset> assets = CborDeserializer.decodeNullable(
            data.get(0),
            result -> CborDeserializer.decodeArray(result).stream()
                    .map(asset -> CborDeserializer.decodeNullable(asset, Asset::fromCbor))
                    .collect(Collectors.toSet())
    );

    SplitReason reason = CborDeserializer.decodeNullable(data.get(1), SplitReason::fromCbor);

    return new TestSplitPaymentData(assets, reason);
  }

  /**
   * Encode split payment data to CBOR bytes.
   *
   * @return encoded payload
   */
  @Override
  public byte[] encode() {
    return CborSerializer.encodeArray(
            CborSerializer.encodeOptional(
                    this.assets,
                    assets -> CborSerializer.encodeArray(
                            assets.stream().map(asset -> CborSerializer.encodeOptional(asset, Asset::toCbor)).toArray(byte[][]::new)
                    )
            ),
            CborSerializer.encodeOptional(this.reason, SplitReason::toCbor)
    );
  }

  @Override
  public String toString() {
    return String.format("SplitPaymentData{assets=%s, reason=%s}", this.assets, this.reason);
  }
}
