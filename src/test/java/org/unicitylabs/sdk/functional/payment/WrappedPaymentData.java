package org.unicitylabs.sdk.functional.payment;

import org.unicitylabs.sdk.payment.PaymentData;
import org.unicitylabs.sdk.payment.asset.PaymentAssetCollection;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import java.util.List;

/**
 * Application envelope {@code tag(39050, [1, assets, null])}. Defined by the tests only; the SDK
 * has no such rule. Matches the cross-SDK split vectors.
 */
public class WrappedPaymentData implements PaymentData {

  public static final long TAG = 39050;

  private final PaymentAssetCollection assets;

  public WrappedPaymentData(PaymentAssetCollection assets) {
    this.assets = assets;
  }

  public static WrappedPaymentData decode(byte[] bytes) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != TAG) {
      throw new IllegalArgumentException("bad envelope tag");
    }
    List<byte[]> fields = CborDeserializer.decodeArray(tag.getData(), 3);
    if (CborDeserializer.decodeUnsignedInteger(fields.get(0)).asLong() != 1) {
      throw new IllegalArgumentException("bad envelope version");
    }
    return new WrappedPaymentData(PaymentAssetCollection.fromCbor(fields.get(1)));
  }

  @Override
  public PaymentAssetCollection getAssets() {
    return this.assets;
  }

  @Override
  public byte[] encode() {
    return CborSerializer.encodeTag(TAG, CborSerializer.encodeArray(
            CborSerializer.encodeUnsignedInteger(1),
            this.assets.toCbor(),
            CborSerializer.encodeNull()));
  }
}
