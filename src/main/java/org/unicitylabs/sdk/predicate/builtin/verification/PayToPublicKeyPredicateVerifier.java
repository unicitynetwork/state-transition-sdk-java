package org.unicitylabs.sdk.predicate.builtin.verification;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.Signature;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.builtin.BuiltInPredicateType;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class PayToPublicKeyPredicateVerifier implements BuiltInPredicateVerifier {

  @Override
  public BuiltInPredicateType getType() {
    return BuiltInPredicateType.PAY_TO_PUBLIC_KEY;
  }


  @Override
  public VerificationResult<VerificationStatus> verify(Predicate encodedPredicate,
      DataHash sourceStateHash,
      DataHash transactionHash, byte[] unlockScript) {
    var predicate = PayToPublicKeyPredicate.fromPredicate(encodedPredicate);

    var result = SigningService.verifyWithPublicKey(
        new DataHasher(HashAlgorithm.SHA256)
            .update(
                CborSerializer.encodeArray(
                    CborSerializer.encodeByteString(sourceStateHash.getData()),
                    CborSerializer.encodeByteString(transactionHash.getData())
                )
            )
            .digest(),
        Signature.decode(unlockScript).getBytes(),
        predicate.getPublicKey()
    );

    if (!result) {
      return new VerificationResult<>("PayToPublicKeyPredicateVerifier", VerificationStatus.FAIL,
          "Signature verification failed.");
    }

    return new VerificationResult<>("PayToPublicKeyPredicateVerifier", VerificationStatus.OK);
  }
}
