package org.unicitylabs.sdk.functional.payment;

import java.util.Set;
import java.util.stream.Collectors;
import org.unicitylabs.sdk.payment.PaymentData;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

public class TestPaymentData implements PaymentData {

  private final Set<Asset> assets;

  public TestPaymentData(Set<Asset> assets) {
    this.assets = Set.copyOf(assets);
  }

  @Override
  public Set<Asset> getAssets() {
    return this.assets;
  }

  public static TestPaymentData decode(byte[] bytes) {
    Set<Asset> assets = CborDeserializer.decodeArray(bytes).stream()
        .map(Asset::fromCbor)
        .collect(Collectors.toSet());

    return new TestPaymentData(assets);
  }

  @Override
  public byte[] encode() {
    return CborSerializer.encodeArray(
        this.assets.stream()
            .map(Asset::toCbor)
            .toArray(byte[][]::new)
    );
  }
}