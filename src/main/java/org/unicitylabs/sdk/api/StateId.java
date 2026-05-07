package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.Objects;

/**
 * Represents a state identifier for requests.
 */
public class StateId {

  private final DataHash hash;

  private StateId(DataHash hash) {
    this.hash = hash;
  }

  /**
   * Returns the raw hash bytes of this state id.
   *
   * @return state id hash bytes
   */
  public byte[] getData() {
    return this.hash.getData();
  }

  /**
   * Deserializes a state id from CBOR.
   *
   * @param bytes CBOR byte string containing SHA-256 hash bytes
   * @return decoded state id
   */
  public static StateId fromCbor(byte[] bytes) {
    return new StateId(
            new DataHash(HashAlgorithm.SHA256, CborDeserializer.decodeByteString(bytes)));
  }

  /**
   * Creates a state id from certification data.
   *
   * @param certificationData certification data carrying lock script and source state hash
   * @return created state id
   * @throws NullPointerException if {@code certificationData} is {@code null}
   */
  public static StateId fromCertificationData(CertificationData certificationData) {
    Objects.requireNonNull(certificationData, "Certification data cannot be null");

    return StateId.create(certificationData.getLockScript(),
            certificationData.getSourceStateHash());
  }

  /**
   * Creates a state id from transaction data.
   *
   * @param transaction transaction carrying lock script and source state hash
   * @return created state id
   * @throws NullPointerException if {@code transaction} is {@code null}
   */
  public static StateId fromTransaction(Transaction transaction) {
    Objects.requireNonNull(transaction, "Transaction cannot be null");

    return StateId.create(transaction.getLockScript(), transaction.getSourceStateHash());
  }

  private static StateId create(EncodedPredicate predicate, DataHash stateHash) {
    DataHash hash = new DataHasher(HashAlgorithm.SHA256)
            .update(
                    CborSerializer.encodeArray(
                            predicate.toCbor(),
                            CborSerializer.encodeByteString(stateHash.getData())
                    )
            )
            .digest();

    return new StateId(hash);
  }

  /**
   * Serializes this state id as a CBOR bytes.
   *
   * @return CBOR-encoded state id
   */
  public byte[] toCbor() {
    return CborSerializer.encodeByteString(this.getData());
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

  @Override
  public String toString() {
    return String.format("StateId[%s]", HexConverter.encode(this.getData()));
  }
}
