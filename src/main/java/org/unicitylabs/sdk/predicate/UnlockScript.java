package org.unicitylabs.sdk.predicate;

/**
 * Contract for predicate unlock script payloads.
 */
public interface UnlockScript {
  /**
   * Encodes this unlock script into bytes.
   *
   * @return encoded unlock script
   */
  byte[] encode();
}
