package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.Predicate;

/**
 * Common interface for token transactions.
 */
public interface Transaction {

  /**
   * Get transaction payload bytes.
   *
   * @return payload bytes
   */
  byte[] getData();

  /**
   * Gets the predicate that locks this transaction.
   *
   * @return lock script predicate
   */
  Predicate getLockScript();

  /**
   * Gets the transaction recipient address.
   *
   * @return recipient address
   */
  Address getRecipient();

  /**
   * Gets the source state hash.
   *
   * @return source state hash
   */
  DataHash getSourceStateHash();

  /**
   * Get transaction randomness component.
   *
   * @return randomness bytes
   */
  byte[] getNonce();

  /**
   * Calculates the resulting state hash.
   *
   * @return state hash
   */
  DataHash calculateStateHash();

  /**
   * Calculates the transaction hash.
   *
   * @return transaction hash
   */
  DataHash calculateTransactionHash();

  /**
   * Serializes this transaction as CBOR.
   *
   * @return CBOR bytes
   */
  byte[] toCbor();
}
