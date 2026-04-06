package org.unicitylabs.sdk.transaction;

import java.util.Arrays;
import java.util.List;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Transfer transaction that moves token ownership from a source state to a recipient.
 */
public class TransferTransaction implements Transaction {

  private final DataHash sourceStateHash;
  private final Predicate lockScript;
  private final Address recipient;
  private final byte[] nonce;
  private final byte[] data;

  private TransferTransaction(
      DataHash sourceStateHash,
      Predicate lockScript,
      Address recipient,
      byte[] x,
      byte[] data
  ) {
    this.sourceStateHash = sourceStateHash;
    this.lockScript = lockScript;
    this.recipient = recipient;
    this.nonce = x;
    this.data = data;
  }


  @Override
  public byte[] getData() {
    return Arrays.copyOf(this.data, this.data.length);
  }

  @Override
  public Predicate getLockScript() {
    return this.lockScript;
  }

  @Override
  public Address getRecipient() {
    return this.recipient;
  }

  @Override
  public DataHash getSourceStateHash() {
    return this.sourceStateHash;
  }

  @Override
  public byte[] getNonce() {
    return Arrays.copyOf(this.nonce, this.nonce.length);
  }

  /**
   * Creates a transfer transaction from the latest state of the provided token.
   *
   * @param token token whose latest transaction is used as the source
   * @param owner current owner predicate
   * @param recipient recipient address
   * @param x transaction randomness component
   * @param data transfer payload
   * @return created transfer transaction
   * @throws RuntimeException if the owner predicate does not match the latest recipient
   */
  public static TransferTransaction create(Token token, Predicate owner, Address recipient,
      byte[] x, byte[] data) {
    Transaction transaction = token.getLatestTransaction();
    if (!transaction.getRecipient().equals(Address.fromPredicate(owner))) {
      throw new RuntimeException("Predicate does not match pay to script hash.");
    }

    return new TransferTransaction(
        transaction.calculateStateHash(),
        owner,
        recipient,
        x,
        data
    );
  }

  /**
   * Deserializes a transfer transaction from CBOR bytes.
   *
   * @param bytes CBOR-encoded transfer transaction
   * @return decoded transfer transaction
   */
  public static TransferTransaction fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new TransferTransaction(
        new DataHash(HashAlgorithm.SHA256, CborDeserializer.decodeByteString(data.get(0))),
        EncodedPredicate.fromCbor(data.get(1)),
        Address.fromCbor(data.get(2)),
        CborDeserializer.decodeByteString(data.get(3)),
        CborDeserializer.decodeByteString(data.get(4))
    );
  }

  @Override
  public DataHash calculateStateHash() {
    return new DataHasher(HashAlgorithm.SHA256)
        .update(
            CborSerializer.encodeArray(
                CborSerializer.encodeByteString(this.sourceStateHash.getImprint()),
                CborSerializer.encodeByteString(this.nonce)
            )
        )
        .digest();
  }

  @Override
  public DataHash calculateTransactionHash() {
    return new DataHasher(HashAlgorithm.SHA256)
        .update(
            CborSerializer.encodeArray(
                this.recipient.toCbor(),
                CborSerializer.encodeByteString(this.nonce),
                CborSerializer.encodeByteString(this.data)
            )
        )
        .digest();
  }

  @Override
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        CborSerializer.encodeByteString(this.sourceStateHash.getData()),
        EncodedPredicate.fromPredicate(this.lockScript).toCbor(),
        this.recipient.toCbor(),
        CborSerializer.encodeByteString(this.nonce),
        CborSerializer.encodeByteString(this.data)
    );
  }

  /**
   * Converts this transfer transaction to a certified transfer transaction.
   *
   * @param trustBase trust base used for proof verification
   * @param predicateVerifier predicate verifier service
   * @param inclusionProof inclusion proof for this transaction
   * @return certified transfer transaction
   */
  public CertifiedTransferTransaction toCertifiedTransaction(
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      InclusionProof inclusionProof
  ) {
    return CertifiedTransferTransaction.fromTransaction(trustBase, predicateVerifier, this,
        inclusionProof);
  }

  @Override
  public String toString() {
    return String.format(
        "TransferTransaction{sourceStateHash=%s, lockScript=%s, recipient=%s, x=%s, data=%s}",
        this.sourceStateHash, this.lockScript, this.recipient, HexConverter.encode(this.nonce),
        HexConverter.encode(this.data));
  }
}
