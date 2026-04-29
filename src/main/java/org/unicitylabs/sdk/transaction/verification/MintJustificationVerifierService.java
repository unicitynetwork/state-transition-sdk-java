package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.transaction.CertifiedMintTransaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * Dispatcher for {@link MintJustificationVerifier} implementations. Verifiers are registered
 * by their CBOR tag; on {@link #verify(CertifiedMintTransaction)} the service reads the tag of
 * the mint transaction's justification and routes the verification to the matching verifier.
 *
 * <p>Mint transactions with no justification are accepted as OK without further checks.
 */
public class MintJustificationVerifierService {
  private final Map<Long, MintJustificationVerifier> verifiers = new HashMap<>();

  /**
   * Register a verifier for its declared tag. Each tag may be registered only once.
   *
   * @param verifier verifier to register
   *
   * @return this service for fluent chaining
   *
   * @throws IllegalArgumentException if a verifier for the same tag is already registered
   */
  public MintJustificationVerifierService register(MintJustificationVerifier verifier) {
    if (this.verifiers.containsKey(verifier.getTag())) {
      throw new IllegalArgumentException(String.format("Duplicate mint justification verifier for tag %s.", verifier.getTag()));
    }

    this.verifiers.put(verifier.getTag(), verifier);
    return this;
  }

  /**
   * Verify the mint justification carried by the given transaction.
   *
   * @param transaction certified mint transaction to verify
   *
   * @return verification result; OK if the transaction has no justification, otherwise the result
   *     of the verifier registered for the justification's CBOR tag
   */
  public VerificationResult<VerificationStatus> verify(CertifiedMintTransaction transaction) {
    byte[] bytes = transaction.getJustification().orElse(null);
    if (bytes == null) {
      return new VerificationResult<>("MintJustificationVerification", VerificationStatus.OK);
    }

    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    MintJustificationVerifier verifier = this.verifiers.get(tag.getTag());
    if (verifier == null) {
      return new VerificationResult<>(
              "MintJustificationVerification",
              VerificationStatus.FAIL,
              String.format("Unsupported mint justification tag: %s", tag.getTag())
      );
    }

    VerificationResult<VerificationStatus> result = verifier.verify(transaction, this);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("MintJustificationVerification", VerificationStatus.FAIL, String.format("Verification failed for tag %s", tag.getTag()), result);
    }

    return new VerificationResult<>("MintJustificationVerification", VerificationStatus.OK, "", result);
  }
}
