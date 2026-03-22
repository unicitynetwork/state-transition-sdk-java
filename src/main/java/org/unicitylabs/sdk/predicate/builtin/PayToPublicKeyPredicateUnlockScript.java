package org.unicitylabs.sdk.predicate.builtin;

import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.Signature;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Transaction;

public class PayToPublicKeyPredicateUnlockScript {

  private final Signature signature;

  private PayToPublicKeyPredicateUnlockScript(Signature signature) {
    this.signature = signature;
  }

  public Signature getSignature() {
    return this.signature;
  }

  public static PayToPublicKeyPredicateUnlockScript create(
      Transaction transaction,
      SigningService signingService
  ) {
    var hash = new DataHasher(HashAlgorithm.SHA256)
        .update(
            CborSerializer.encodeArray(
                CborSerializer.encodeByteString(transaction.getSourceStateHash().getData()),
                CborSerializer.encodeByteString(transaction.calculateTransactionHash().getData())
            )
        )
        .digest();

    return new PayToPublicKeyPredicateUnlockScript(signingService.sign(hash));
  }

  public static PayToPublicKeyPredicateUnlockScript decode(byte[] bytes) {
    return new PayToPublicKeyPredicateUnlockScript(Signature.decode(bytes));
  }

  public byte[] encode() {
    return this.signature.encode();
  }


}
