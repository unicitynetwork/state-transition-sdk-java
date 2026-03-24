package org.unicitylabs.sdk.functional.payment;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.unicitylabs.sdk.payment.SplitPaymentData;
import org.unicitylabs.sdk.payment.SplitReason;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

public class TestSplitPaymentData implements SplitPaymentData {
  private final Set<Asset> assets;
  private final SplitReason reason;

  public TestSplitPaymentData(Set<Asset> assets, SplitReason reason) {
    this.assets = Set.copyOf(assets);
    this.reason = reason;
  }

  public Set<Asset> getAssets() {
    return this.assets;
  }

  @Override
  public SplitReason getReason() {
    return this.reason;
  }

  public static TestSplitPaymentData decode(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    Set<Asset> assets = CborDeserializer.decodeArray(data.get(0)).stream()
        .map(Asset::fromCbor)
        .collect(Collectors.toSet());

    SplitReason reason = SplitReason.fromCbor(data.get(1));

    return new TestSplitPaymentData(assets, reason);
  }

  @Override
  public byte[] encode() {
    return CborSerializer.encodeArray(
        CborSerializer.encodeArray(
            this.assets.stream().map(Asset::toCbor).toArray(byte[][]::new)
        ),
        this.reason.toCbor()
    );
  }
}