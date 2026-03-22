package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.Predicate;

public interface Transaction {

  byte[] getData();

  Predicate getLockScript();

  Address getRecipient();

  DataHash getSourceStateHash();

  byte[] getX();

  DataHash calculateStateHash();

  DataHash calculateTransactionHash();

  byte[] toCbor();
}
