package org.unicitylabs.sdk.payment;

import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.PaymentAssetCollection;

import java.util.List;
import java.util.Objects;

/**
 * Frozen payment payload of one split output: the declared assets plus the exact bytes that are
 * committed by the burn and minted. Instances only come from {@link #freeze}, so the bytes are
 * always the ones the split's decoder maps back to the declared assets, and {@link #encode()}
 * always returns a copy of those same bytes regardless of the payload it was frozen from.
 */
public final class SplitOutputPayload implements PaymentData {

  private final PaymentAssetCollection assets;
  private final byte[] bytes;

  private SplitOutputPayload(PaymentAssetCollection assets, byte[] bytes) {
    this.assets = assets;
    this.bytes = bytes;
  }

  /**
   * Freeze a payment payload for a split output. Encodes once, copies the bytes, decodes a copy
   * with {@code decoder} and requires the decoded assets to equal the declared assets (ids and
   * values, in order).
   *
   * @param declared payment data of the output
   * @param decoder the decoder passed to the split (the one verification will use)
   * @return frozen payload
   * @throws SplitPayloadMismatchException if the bytes do not decode to the declared assets
   */
  public static SplitOutputPayload freeze(PaymentData declared, PaymentDataDeserializer decoder) {
    Objects.requireNonNull(declared, "Payment data cannot be null");
    Objects.requireNonNull(decoder, "Payment data deserializer cannot be null");

    PaymentAssetCollection declaredAssets = declared.getAssets();
    byte[] encoded = declared.encode();
    if (declaredAssets == null || encoded == null) {
      throw new SplitPayloadMismatchException("Split output payment data is incomplete.");
    }
    byte[] bytes = encoded.clone();

    PaymentData decoded = decoder.decode(bytes.clone());
    PaymentAssetCollection decodedAssets = decoded == null ? null : decoded.getAssets();
    if (decodedAssets == null || !sameAssets(declaredAssets, decodedAssets)) {
      throw new SplitPayloadMismatchException(
              "Split output payload does not decode to the declared assets.");
    }

    return new SplitOutputPayload(declaredAssets, bytes);
  }

  // Asset.equals compares ids only, so values are compared explicitly.
  private static boolean sameAssets(PaymentAssetCollection a, PaymentAssetCollection b) {
    List<Asset> x = a.toList();
    List<Asset> y = b.toList();
    if (x.size() != y.size()) {
      return false;
    }
    for (int i = 0; i < x.size(); i++) {
      if (!x.get(i).getId().equals(y.get(i).getId())
              || !x.get(i).getValue().equals(y.get(i).getValue())) {
        return false;
      }
    }
    return true;
  }

  @Override
  public PaymentAssetCollection getAssets() {
    return this.assets;
  }

  /**
   * Returns a copy of the committed bytes.
   *
   * @return encoded payment data
   */
  @Override
  public byte[] encode() {
    return this.bytes.clone();
  }
}
