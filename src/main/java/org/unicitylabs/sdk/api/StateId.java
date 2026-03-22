package org.unicitylabs.sdk.api;

import java.util.Objects;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.BitString;
import org.unicitylabs.sdk.util.HexConverter;

public class StateId {

  private final DataHash hash;

  private StateId(DataHash hash) {
    this.hash = hash;
  }

  public byte[] getData() {
    return this.hash.getData();
  }

  public byte[] getImprint() {
    return this.hash.getImprint();
  }

  public static StateId fromCbor(byte[] bytes) {
    return new StateId(
        new DataHash(HashAlgorithm.SHA256, CborDeserializer.decodeByteString(bytes)));
  }

  public static StateId fromCertificationData(CertificationData certificationData) {
    Objects.requireNonNull(certificationData, "Certification data cannot be null");

    return StateId.create(certificationData.getLockScript(),
        certificationData.getSourceStateHash());
  }

  public static StateId fromTransaction(Transaction transaction) {
    Objects.requireNonNull(transaction, "Transaction cannot be null");

    return StateId.create(transaction.getLockScript(), transaction.getSourceStateHash());
  }

  private static StateId create(Predicate predicate, DataHash stateHash) {
    DataHash hash = new DataHasher(HashAlgorithm.SHA256)
        .update(
            CborSerializer.encodeArray(
                EncodedPredicate.fromPredicate(predicate).toCbor(),
                CborSerializer.encodeByteString(stateHash.getData())
            )
        )
        .digest();

    return new StateId(hash);
  }

  public byte[] toCbor() {
    return CborSerializer.encodeByteString(this.getData());
  }

  /**
   * Converts the StateId to a BitString.
   *
   * @return The BitString representation of the StateId.
   */
  public BitString toBitString() {
    return BitString.fromStateId(this);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof StateId)) {
      return false;
    }
    StateId stateId = (StateId) o;
    return Objects.equals(this.hash, stateId.hash);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.hash);
  }

  /**
   * Returns a string representation of the StateId.
   *
   * @return The string representation.
   */
  @Override
  public String toString() {
    return String.format("StateId[%s]", HexConverter.encode(this.getImprint()));
  }
}