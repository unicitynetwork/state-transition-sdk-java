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

public class TransferTransaction implements Transaction {
  public static final long CBOR_TAG = 39045;
  private static final int VERSION = 1;

  private final DataHash sourceStateHash;
  private final Predicate lockScript;
  private final Address recipient;
  private final byte[] x;
  private final byte[] data;

  private TransferTransaction(DataHash sourceStateHash, Predicate lockScript, Address recipient,
      byte[] x,
      byte[] data) {
    this.sourceStateHash = sourceStateHash;
    this.lockScript = lockScript;
    this.recipient = recipient;
    this.x = x;
    this.data = data;
  }

  public int getVersion() {
    return TransferTransaction.VERSION;
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
  public byte[] getX() {
    return Arrays.copyOf(this.x, this.x.length);
  }

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

  public static TransferTransaction fromCbor(byte[] bytes) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != TransferTransaction.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData());

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != TransferTransaction.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return new TransferTransaction(
        new DataHash(HashAlgorithm.SHA256, CborDeserializer.decodeByteString(data.get(1))),
        EncodedPredicate.fromCbor(data.get(2)),
        Address.fromCbor(data.get(3)),
        CborDeserializer.decodeByteString(data.get(4)),
        CborDeserializer.decodeByteString(data.get(5))
    );
  }

  @Override
  public DataHash calculateStateHash() {
    return new DataHasher(HashAlgorithm.SHA256)
        .update(
            CborSerializer.encodeArray(
                CborSerializer.encodeByteString(this.sourceStateHash.getImprint()),
                CborSerializer.encodeByteString(this.x)
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
                CborSerializer.encodeByteString(this.x),
                CborSerializer.encodeByteString(this.data)
            )
        )
        .digest();
  }

  @Override
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
            TransferTransaction.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(TransferTransaction.VERSION),
                    CborSerializer.encodeByteString(this.sourceStateHash.getData()),
                    EncodedPredicate.fromPredicate(this.lockScript).toCbor(),
                    this.recipient.toCbor(),
                    CborSerializer.encodeByteString(this.x),
                    CborSerializer.encodeByteString(this.data)
            )
    );
  }

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
        this.sourceStateHash, this.lockScript, this.recipient, HexConverter.encode(this.x),
        HexConverter.encode(this.data));
  }
}
