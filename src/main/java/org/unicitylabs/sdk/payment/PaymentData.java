package org.unicitylabs.sdk.payment;

import java.util.Set;
import org.unicitylabs.sdk.payment.asset.Asset;

/**
 * Represents payment payload data.
 */
public interface PaymentData {
  /**
   * Returns the assets included in this payment payload.
   *
   * @return set of assets
   */
  Set<Asset> getAssets();

  /**
   * Encodes this payment payload into bytes.
   *
   * @return encoded payment data
   */
  byte[] encode();
}
