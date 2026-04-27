package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationRule;
import org.unicitylabs.sdk.transaction.verification.InclusionProofVerificationStatus;
import org.unicitylabs.sdk.util.verification.VerificationException;
import org.unicitylabs.sdk.util.verification.VerificationResult;

import java.util.List;

/**
 * Transfer transaction with a verified inclusion proof.
 */
public class CertifiedTransferTransaction implements Transaction {

  private final TransferTransaction transaction;
  private final InclusionProof inclusionProof;

  private CertifiedTransferTransaction(
          TransferTransaction transaction,
          InclusionProof inclusionProof
  ) {
    this.transaction = transaction;
    this.inclusionProof = inclusionProof;
  }

  /**
   * Get transaction payload data.
   *
   * @return payload data bytes
   */
  @Override
  public byte[] getData() {
    return this.transaction.getData();
  }

  /**
   * Get predicate locking script for this transaction output.
   *
   * @return lock script predicate
   */
  @Override
  public Predicate getLockScript() {
    return this.transaction.getLockScript();
  }

  /**
   * Get recipient address of this transaction.
   *
   * @return recipient address
   */
  @Override
  public Address getRecipient() {
    return this.transaction.getRecipient();
  }

  /**
   * Get source state hash of this transaction.
   *
   * @return source state hash
   */
  @Override
  public DataHash getSourceStateHash() {
    return this.transaction.getSourceStateHash();
  }

  /**
   * Get transaction chosen random bytes.
   *
   * @return random bytes
   */
  @Override
  public byte[] getNonce() {
    return this.transaction.getNonce();
  }

  /**
   * Get inclusion proof for this transaction.
   *
   * @return inclusion proof
   */
  public InclusionProof getInclusionProof() {
    return this.inclusionProof;
  }

  /**
   * Deserialize a certified transfer transaction from CBOR bytes.
   *
   * @param bytes CBOR encoded certified transfer transaction
   *
   * @return certified transfer transaction
   */
  public static CertifiedTransferTransaction fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new CertifiedTransferTransaction(TransferTransaction.fromCbor(data.get(0)),
            InclusionProof.fromCbor(data.get(1)));
  }

  /**
   * Create a certified transfer transaction from a transfer transaction and inclusion proof.
   *
   * <p>The inclusion proof is verified against the transaction before creating the certified
   * instance.
   *
   * @param trustBase trust base used for proof verification
   * @param predicateVerifier predicate verifier used by verification rules
   * @param transaction transfer transaction
   * @param inclusionProof inclusion proof
   *
   * @return certified transfer transaction
   *
   * @throws VerificationException if inclusion proof verification fails
   */
  public static CertifiedTransferTransaction fromTransaction(RootTrustBase trustBase,
                                                             PredicateVerifierService predicateVerifier, TransferTransaction transaction,
                                                             InclusionProof inclusionProof) {
    VerificationResult<InclusionProofVerificationStatus> result = InclusionProofVerificationRule.verify(
            trustBase,
            predicateVerifier,
            inclusionProof,
            transaction
    );
    if (result.getStatus() != InclusionProofVerificationStatus.OK) {
      throw new VerificationException("Inclusion proof verification failed", result);
    }

    return new CertifiedTransferTransaction(transaction, inclusionProof);
  }

  /**
   * Calculate state hash of the transfer transaction.
   *
   * @return state hash
   */
  @Override
  public DataHash calculateStateHash() {
    return this.transaction.calculateStateHash();
  }

  /**
   * Calculate hash of the transfer transaction.
   *
   * @return transaction hash
   */
  @Override
  public DataHash calculateTransactionHash() {
    return this.transaction.calculateTransactionHash();
  }

  /**
   * Serialize this certified transfer transaction to CBOR bytes.
   *
   * @return CBOR bytes
   */
  @Override
  public byte[] toCbor() {
    return CborSerializer.encodeArray(this.transaction.toCbor(), this.inclusionProof.toCbor());
  }

  @Override
  public String toString() {
    return String.format("CertifiedTransferTransaction{transaction=%s, inclusionProof=%s}",
            this.transaction, this.inclusionProof);
  }
}
