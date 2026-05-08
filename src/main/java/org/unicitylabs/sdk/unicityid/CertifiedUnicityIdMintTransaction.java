package org.unicitylabs.sdk.unicityid;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationRule;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationStatus;
import org.unicitylabs.sdk.util.verification.VerificationException;
import org.unicitylabs.sdk.util.verification.VerificationResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Unicity id mint transaction bundled with a verified inclusion proof.
 */
public final class CertifiedUnicityIdMintTransaction implements Transaction {

  private final UnicityIdMintTransaction transaction;
  private final InclusionProof inclusionProof;

  private CertifiedUnicityIdMintTransaction(UnicityIdMintTransaction transaction,
                                            InclusionProof inclusionProof) {
    this.transaction = transaction;
    this.inclusionProof = inclusionProof;
  }

  @Override
  public Optional<byte[]> getData() {
    return this.transaction.getData();
  }

  @Override
  public EncodedPredicate getLockScript() {
    return this.transaction.getLockScript();
  }

  @Override
  public EncodedPredicate getRecipient() {
    return this.transaction.getRecipient();
  }

  @Override
  public DataHash getSourceStateHash() {
    return this.transaction.getSourceStateHash();
  }

  @Override
  public byte[] getStateMask() {
    return this.transaction.getStateMask();
  }

  /**
   * Returns the token id derived from the unicity id.
   *
   * @return token id
   */
  public TokenId getTokenId() {
    return this.transaction.getTokenId();
  }

  /**
   * Returns the token type.
   *
   * @return token type
   */
  public TokenType getTokenType() {
    return this.transaction.getTokenType();
  }

  /**
   * Returns the target predicate.
   *
   * @return target predicate
   */
  public SignaturePredicate getTargetPredicate() {
    return this.transaction.getTargetPredicate();
  }

  /**
   * Returns the unicity id.
   *
   * @return unicity id
   */
  public UnicityId getUnicityId() {
    return this.transaction.getUnicityId();
  }

  /**
   * Returns the inclusion proof certifying this transaction.
   *
   * @return inclusion proof
   */
  public InclusionProof getInclusionProof() {
    return this.inclusionProof;
  }

  /**
   * Deserializes a certified unicity id mint transaction from CBOR.
   *
   * @param bytes CBOR-encoded certified mint transaction
   *
   * @return decoded certified mint transaction
   */
  public static CertifiedUnicityIdMintTransaction fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes, 2);
    return new CertifiedUnicityIdMintTransaction(
            UnicityIdMintTransaction.fromCbor(data.get(0)),
            InclusionProof.fromCbor(data.get(1))
    );
  }

  /**
   * Creates a certified unicity id mint transaction after verifying its inclusion proof.
   *
   * @param trustBase trust base used to verify inclusion proof signatures
   * @param predicateVerifier predicate verifier service
   * @param transaction unicity id mint transaction to certify
   * @param inclusionProof inclusion proof for the transaction
   *
   * @return certified mint transaction
   *
   * @throws VerificationException if inclusion proof verification fails
   */
  public static CertifiedUnicityIdMintTransaction fromTransaction(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          UnicityIdMintTransaction transaction,
          InclusionProof inclusionProof
  ) {
    Objects.requireNonNull(trustBase, "trustBase cannot be null");
    Objects.requireNonNull(predicateVerifier, "predicateVerifier cannot be null");
    Objects.requireNonNull(transaction, "transaction cannot be null");
    Objects.requireNonNull(inclusionProof, "inclusionProof cannot be null");

    VerificationResult<InclusionProofVerificationStatus> result = InclusionProofVerificationRule.verify(
            trustBase,
            predicateVerifier,
            inclusionProof,
            transaction
    );
    if (result.getStatus() != InclusionProofVerificationStatus.OK) {
      throw new VerificationException("Inclusion proof verification failed", result);
    }

    return new CertifiedUnicityIdMintTransaction(transaction, inclusionProof);
  }

  @Override
  public DataHash calculateStateHash() {
    return this.transaction.calculateStateHash();
  }

  @Override
  public DataHash calculateTransactionHash() {
    return this.transaction.calculateTransactionHash();
  }

  @Override
  public byte[] toCbor() {
    return CborSerializer.encodeArray(this.transaction.toCbor(), this.inclusionProof.toCbor());
  }

  @Override
  public String toString() {
    return String.format("CertifiedUnicityIdMintTransaction{transaction=%s, inclusionProof=%s}",
            this.transaction, this.inclusionProof);
  }
}
