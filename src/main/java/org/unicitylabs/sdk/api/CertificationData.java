package org.unicitylabs.sdk.api;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.crypto.MintSigningService;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.UnlockScript;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicateUnlockScript;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Certification data.
 */
public class CertificationData {

  private final Predicate lockScript;
  private final DataHash sourceStateHash;
  private final DataHash transactionHash;
  private final byte[] unlockScript;

  CertificationData(
      Predicate lockScript,
      DataHash sourceStateHash,
      DataHash transactionHash,
      byte[] unlockScript
  ) {
    this.lockScript = lockScript;
    this.sourceStateHash = sourceStateHash;
    this.transactionHash = transactionHash;
    this.unlockScript = Arrays.copyOf(unlockScript, unlockScript.length);
  }

  /**
   * Get lock script of certified transaction output.
   *
   * @return lock script
   */
  public Predicate getLockScript() {
    return this.lockScript;
  }

  /**
   * Get source state hash.
   *
   * @return source state hash
   */
  public DataHash getSourceStateHash() {
    return this.sourceStateHash;
  }

  /**
   * Get transaction hash.
   *
   * @return transaction hash
   */
  public DataHash getTransactionHash() {
    return this.transactionHash;
  }

  /**
   * Get unlock script used for certification.
   *
   * @return unlock script bytes
   */
  public byte[] getUnlockScript() {
    return Arrays.copyOf(this.unlockScript, this.unlockScript.length);
  }

  /**
   * Deserialize CertificationData from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return CertificationData
   */
  public static CertificationData fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new CertificationData(
        EncodedPredicate.fromCbor(data.get(0)),
        new DataHash(HashAlgorithm.SHA256, CborDeserializer.decodeByteString(data.get(1))),
        new DataHash(HashAlgorithm.SHA256, CborDeserializer.decodeByteString(data.get(2))),
        CborDeserializer.decodeByteString(data.get(3))
    );
  }

  /**
   * Build certification data for a mint transaction using the deterministic mint signing service.
   *
   * @param transaction mint transaction
   *
   * @return certification data
   */
  public static CertificationData fromMintTransaction(MintTransaction transaction) {
    SigningService signingService = MintSigningService.create(transaction.getTokenId());

    return CertificationData.fromTransaction(
        transaction,
        PayToPublicKeyPredicateUnlockScript.create(transaction, signingService).getSignature()
            .encode()
    );
  }

  /**
   * Build certification data from a transaction and unlock script object.
   *
   * @param transaction transaction to certify
   * @param unlockScript unlock script
   *
   * @return certification data
   */
  public static CertificationData fromTransaction(Transaction transaction, UnlockScript unlockScript) {
    return CertificationData.fromTransaction(transaction, unlockScript.encode());
  }

  /**
   * Build certification data from a transaction and encoded unlock script bytes.
   *
   * @param transaction transaction to certify
   * @param unlockScript encoded unlock script bytes
   *
   * @return certification data
   */
  public static CertificationData fromTransaction(Transaction transaction, byte[] unlockScript) {
    return new CertificationData(
        transaction.getLockScript(),
        transaction.getSourceStateHash(),
        transaction.calculateTransactionHash(),
        unlockScript
    );
  }

  /**
   * Calculate leaf value for Merkle tree.
   *
   * @return leaf value
   */
  public DataHash calculateLeafValue() {
    return new DataHasher(HashAlgorithm.SHA256)
        .update(this.toCbor())
        .digest();
  }

  /**
   * Serialize certification data to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        EncodedPredicate.fromPredicate(this.getLockScript()).toCbor(),
        CborSerializer.encodeByteString(this.sourceStateHash.getData()),
        CborSerializer.encodeByteString(this.transactionHash.getData()),
        CborSerializer.encodeByteString(this.unlockScript)
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof CertificationData)) {
      return false;
    }
    CertificationData that = (CertificationData) o;
    return this.lockScript.isEqualTo(that.lockScript)
        && Objects.equals(this.sourceStateHash, that.sourceStateHash)
        && Objects.equals(this.transactionHash, that.transactionHash)
        && Arrays.equals(this.unlockScript, that.unlockScript);
  }

  @Override
  public String toString() {
    return String.format(
        "CertificationData{lockScript=%s, sourceStateHash=%s, transactionHash=%s, unlockScript=%s}",
        this.lockScript, this.sourceStateHash, this.transactionHash,
        HexConverter.encode(this.unlockScript));
  }
}
