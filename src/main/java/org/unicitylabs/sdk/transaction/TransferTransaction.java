package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Transfer transaction that moves token ownership from a source state to a recipient.
 */
public class TransferTransaction implements Transaction {
  public static final long CBOR_TAG = 39045;
  private static final int VERSION = 1;

  private final DataHash sourceStateHash;
  private final Predicate lockScript;
  private final Predicate recipient;
  private final byte[] stateMask;
  private final byte[] data;

  private TransferTransaction(
          DataHash sourceStateHash,
          Predicate lockScript,
          Predicate recipient,
          byte[] stateMask,
          byte[] data
  ) {
    this.sourceStateHash = sourceStateHash;
    this.lockScript = lockScript;
    this.recipient = recipient;
    this.stateMask = stateMask;
    this.data = data;
  }

  public int getVersion() {
    return TransferTransaction.VERSION;
  }


  @Override
  public Optional<byte[]> getData() {
    return Optional.ofNullable(this.data != null ? Arrays.copyOf(this.data, this.data.length) : null);
  }

  @Override
  public Predicate getLockScript() {
    return this.lockScript;
  }

  @Override
  public Predicate getRecipient() {
    return this.recipient;
  }

  @Override
  public DataHash getSourceStateHash() {
    return this.sourceStateHash;
  }

  @Override
  public byte[] getStateMask() {
    return Arrays.copyOf(this.stateMask, this.stateMask.length);
  }

  /**
   * Creates a transfer transaction from the latest state of the provided token.
   *
   * @param token token whose latest transaction is used as the source
   * @param recipient recipient predicate
   * @param stateMask transaction randomness component
   * @param data transfer payload
   * @return created transfer transaction
   */
  public static TransferTransaction create(Token token, Predicate recipient,
                                           byte[] stateMask, byte[] data) {
    Transaction transaction = token.getLatestTransaction();

    return new TransferTransaction(
            transaction.calculateStateHash(),
            transaction.getRecipient(),
            recipient,
            stateMask,
            data
    );
  }

  /**
   * Deserializes a transfer transaction from CBOR bytes.
   *
   * @param bytes CBOR-encoded transfer transaction
   * @param token token providing the source state for the deserialized transfer
   * @return decoded transfer transaction
   */
  public static TransferTransaction fromCbor(byte[] bytes, Token token) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != TransferTransaction.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData());

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != TransferTransaction.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return TransferTransaction.create(
            token,
            EncodedPredicate.fromCbor(data.get(1)),
            CborDeserializer.decodeByteString(data.get(2)),
            CborDeserializer.decodeNullable(data.get(3), CborDeserializer::decodeByteString)
    );
  }

  @Override
  public DataHash calculateStateHash() {
    return new DataHasher(HashAlgorithm.SHA256)
            .update(
                    CborSerializer.encodeArray(
                            CborSerializer.encodeByteString(this.sourceStateHash.getImprint()),
                            CborSerializer.encodeByteString(this.stateMask)
                    )
            )
            .digest();
  }

  @Override
  public DataHash calculateTransactionHash() {
    return new DataHasher(HashAlgorithm.SHA256)
            .update(this.toCbor())
            .digest();
  }

  @Override
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
            TransferTransaction.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(TransferTransaction.VERSION),
                    EncodedPredicate.fromPredicate(this.recipient).toCbor(),
                    CborSerializer.encodeByteString(this.stateMask),
                    CborSerializer.encodeNullable(this.data, CborSerializer::encodeByteString)
            )
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
    return CertifiedTransferTransaction.fromTransaction(
            trustBase,
            predicateVerifier,
            this,
            inclusionProof
    );
  }

  @Override
  public String toString() {
    return String.format(
            "TransferTransaction{sourceStateHash=%s, lockScript=%s, recipient=%s, stateMask=%s, data=%s}",
            this.sourceStateHash, this.lockScript, this.recipient, HexConverter.encode(this.stateMask),
            HexConverter.encode(this.data));
  }
}
