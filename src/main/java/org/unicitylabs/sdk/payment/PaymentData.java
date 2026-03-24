package org.unicitylabs.sdk.payment;

import java.util.Set;
import org.unicitylabs.sdk.payment.asset.Asset;

public interface PaymentData {
  Set<Asset> getAssets();

  byte[] encode();
}
