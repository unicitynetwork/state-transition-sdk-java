package org.unicitylabs.sdk.predicate.builtin;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.Signature;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.UnlockScript;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Transaction;

/**
 * Unlock script for {@link PayToPublicKeyPredicate} containing a transaction signature.
 */
public class PayToPublicKeyPredicateUnlockScript implements UnlockScript {

  private final Signature signature;

  private PayToPublicKeyPredicateUnlockScript(Signature signature) {
    this.signature = signature;
  }

  /**
   * Returns the unlock signature.
   *
   * @return signature used to unlock the predicate
   */
  public Signature getSignature() {
    return this.signature;
  }

  /**
   * Creates an unlock script by signing the source-state and transaction-hash payload.
   *
   * @param transaction transaction being authorized
   * @param signingService signing service used to produce the signature
   * @return created unlock script
   */
  public static PayToPublicKeyPredicateUnlockScript create(
      Transaction transaction,
      SigningService signingService
  ) {
    DataHash hash = new DataHasher(HashAlgorithm.SHA256)
        .update(
            CborSerializer.encodeArray(
                CborSerializer.encodeByteString(transaction.getSourceStateHash().getData()),
                CborSerializer.encodeByteString(transaction.calculateTransactionHash().getData())
            )
        )
        .digest();

    return new PayToPublicKeyPredicateUnlockScript(signingService.sign(hash));
  }

  /**
   * Decodes an unlock script from encoded signature bytes.
   *
   * @param bytes encoded signature bytes
   * @return decoded unlock script
   */
  public static PayToPublicKeyPredicateUnlockScript decode(byte[] bytes) {
    return new PayToPublicKeyPredicateUnlockScript(Signature.decode(bytes));
  }

  @Override
  public byte[] encode() {
    return this.signature.encode();
  }
}
