package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
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
  /** The only accepted wire version. One version, one element count. */
  public static final int VERSION = 2;
  private static final int FIELD_COUNT = 5;

  private final DataHash sourceStateHash;
  private final EncodedPredicate lockScript;
  private final EncodedPredicate recipient;
  private final Long expiresAt;
  private final StateMask stateMask;
  private final byte[] data;

  private TransferTransaction(
          DataHash sourceStateHash,
          EncodedPredicate lockScript,
          EncodedPredicate recipient,
          Long expiresAt,
          StateMask stateMask,
          byte[] data
  ) {
    this.sourceStateHash = sourceStateHash;
    this.lockScript = lockScript;
    this.recipient = recipient;
    this.expiresAt = ExpiresAt.validate(expiresAt);
    this.stateMask = stateMask;
    this.data = data;
  }

  @Override
  public Optional<byte[]> getData() {
    return Optional.ofNullable(this.data != null ? Arrays.copyOf(this.data, this.data.length) : null);
  }

  @Override
  public EncodedPredicate getLockScript() {
    return this.lockScript;
  }

  @Override
  public EncodedPredicate getRecipient() {
    return this.recipient;
  }

  @Override
  public DataHash getSourceStateHash() {
    return this.sourceStateHash;
  }

  @Override
  public StateMask getStateMask() {
    return this.stateMask;
  }

  @Override
  public Optional<Long> getExpiresAt() {
    return Optional.ofNullable(this.expiresAt);
  }

  /**
   * Creates a transfer transaction from the latest state of the provided token.
   *
   * @param token token whose latest transaction is used as the source
   * @param recipient recipient predicate
   * @param stateMask transaction randomness component
   * @param data transfer payload
   * @param expiresAt exclusive request deadline, may be null to let the service assign one
   * @return created transfer transaction
   */
  public static TransferTransaction create(Token token, Predicate recipient,
                                           StateMask stateMask, byte[] data, Long expiresAt) {
    Transaction transaction = token.getLatestTransaction();

    return new TransferTransaction(
            transaction.calculateStateHash(),
            transaction.getRecipient(),
            EncodedPredicate.fromPredicate(recipient),
            expiresAt,
            stateMask,
            data
    );
  }

  /**
   * Creates a transfer whose deadline is assigned by the Unicity Service, which requires no local
   * clock.
   *
   * @param token token whose latest transaction is used as the source
   * @param recipient recipient predicate
   * @param stateMask transaction randomness component
   * @param data transfer payload
   * @return created transfer transaction
   */
  public static TransferTransaction create(Token token, Predicate recipient,
                                           StateMask stateMask, byte[] data) {
    return create(token, recipient, stateMask, data, null);
  }

  /**
   * Deserializes a transfer transaction from CBOR bytes.
   *
   * <p>The state being spent and the lock script over it are chain context rather than part of the
   * encoded transfer, so the caller supplies them. Both are checked against the certification data
   * during verification, so a wrong value fails there rather than yielding a transaction that looks
   * valid.
   *
   * @param bytes CBOR-encoded transfer transaction
   * @param sourceStateHash hash of the state the transaction spends
   * @param lockScript lock script the transaction unlocks
   * @return decoded transfer transaction
   */
  public static TransferTransaction fromCbor(byte[] bytes, DataHash sourceStateHash,
          EncodedPredicate lockScript) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != TransferTransaction.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), FIELD_COUNT);

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return new TransferTransaction(
            sourceStateHash,
            lockScript,
            EncodedPredicate.fromCbor(data.get(1)),
            CborDeserializer.decodeNullable(
                    data.get(4), value -> CborDeserializer.decodeUnsignedInteger(value).asLong()),
            StateMask.fromCbor(data.get(2)),
            CborDeserializer.decodeNullable(data.get(3), CborDeserializer::decodeByteString)
    );
  }

  @Override
  public DataHash calculateStateHash() {
    return new DataHasher(HashAlgorithm.SHA256)
            .update(
                    CborSerializer.encodeArray(
                            CborSerializer.encodeByteString(this.sourceStateHash.getImprint()),
                            this.stateMask.toCbor()
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
                    CborSerializer.encodeUnsignedInteger(VERSION),
                    EncodedPredicate.fromPredicate(this.recipient).toCbor(),
                    this.stateMask.toCbor(),
                    CborSerializer.encodeNullable(this.data, CborSerializer::encodeByteString),
                    CborSerializer.encodeNullable(this.expiresAt, CborSerializer::encodeUnsignedInteger))
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
          TrustBaseHistoryView trustBase,
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
            "TransferTransaction{sourceStateHash=%s, lockScript=%s, recipient=%s, expiresAt=%s, stateMask=%s, data=%s}",
            this.sourceStateHash, this.lockScript, this.recipient, this.expiresAt, this.stateMask,
            HexConverter.encode(this.data));
  }
}
