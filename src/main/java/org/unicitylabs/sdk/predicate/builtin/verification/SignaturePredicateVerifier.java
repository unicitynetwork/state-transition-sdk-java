package org.unicitylabs.sdk.predicate.builtin.verification;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.Signature;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.builtin.BuiltInPredicateType;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Verifies {@link SignaturePredicate} unlock scripts using secp256k1 signatures.
 */
public class SignaturePredicateVerifier implements BuiltInPredicateVerifier {

  /**
   * Creates a verifier instance for pay-to-public-key predicates.
   */
  public SignaturePredicateVerifier() {
  }

  @Override
  public BuiltInPredicateType getType() {
    return BuiltInPredicateType.SIGNATURE;
  }


  @Override
  public VerificationResult<VerificationStatus> verify(EncodedPredicate encodedPredicate,
                                                       DataHash sourceStateHash,
                                                       DataHash transactionHash, byte[] unlockScript) {
    SignaturePredicate predicate = SignaturePredicate.fromPredicate(encodedPredicate);

    boolean result = SigningService.verifyWithPublicKey(
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
      return new VerificationResult<>("SignaturePredicateVerifier", VerificationStatus.FAIL,
              "Signature verification failed.");
    }

    return new VerificationResult<>("SignaturePredicateVerifier", VerificationStatus.OK);
  }
}
