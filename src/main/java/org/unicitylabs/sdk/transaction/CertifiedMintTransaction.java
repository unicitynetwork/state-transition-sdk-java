package org.unicitylabs.sdk.transaction;

import java.util.List;
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

public class CertifiedMintTransaction implements Transaction {

  private final MintTransaction transaction;
  private final InclusionProof inclusionProof;

  private CertifiedMintTransaction(MintTransaction transaction, InclusionProof inclusionProof) {
    this.transaction = transaction;
    this.inclusionProof = inclusionProof;
  }

  @Override
  public byte[] getData() {
    return this.transaction.getData();
  }

  @Override
  public Predicate getLockScript() {
    return this.transaction.getLockScript();
  }

  @Override
  public Address getRecipient() {
    return this.transaction.getRecipient();
  }

  @Override
  public DataHash getSourceStateHash() {
    return this.transaction.getSourceStateHash();
  }

  public TokenId getTokenId() {
    return this.transaction.getTokenId();
  }

  public TokenType getTokenType() {
    return this.transaction.getTokenType();
  }

  @Override
  public byte[] getX() {
    return this.transaction.getX();
  }

  public InclusionProof getInclusionProof() {
    return this.inclusionProof;
  }

  public static CertifiedMintTransaction fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);
    return new CertifiedMintTransaction(MintTransaction.fromCbor(data.get(0)),
        InclusionProof.fromCbor(data.get(1)));
  }

  public static CertifiedMintTransaction fromTransaction(RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier, MintTransaction transaction,
      InclusionProof inclusionProof) {
    VerificationResult<InclusionProofVerificationStatus> result = InclusionProofVerificationRule.verify(trustBase, predicateVerifier, inclusionProof,
        transaction);
    if (result.getStatus() != InclusionProofVerificationStatus.OK) {
      throw new VerificationException("Inclusion proof verification failed", result);
    }

    return new CertifiedMintTransaction(transaction, inclusionProof);
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
    return String.format("CertifiedMintTransaction{transaction=%s, inclusionProof=%s}",
        this.transaction, this.inclusionProof);
  }
}
