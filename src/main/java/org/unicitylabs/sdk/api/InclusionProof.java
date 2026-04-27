package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a proof of inclusion or non-inclusion in a sparse merkle tree.
 */
public class InclusionProof {
  public static final long CBOR_TAG = 39033;
  private static final int VERSION = 1;

  private final InclusionCertificate inclusionCertificate;
  private final CertificationData certificationData;
  private final UnicityCertificate unicityCertificate;

  InclusionProof(
          CertificationData certificationData,
          InclusionCertificate inclusionCertificate,
          UnicityCertificate unicityCertificate
  ) {
    Objects.requireNonNull(unicityCertificate, "Unicity certificate cannot be null.");

    this.inclusionCertificate = inclusionCertificate;
    this.certificationData = certificationData;
    this.unicityCertificate = unicityCertificate;
  }

  public int getVersion() {
    return InclusionProof.VERSION;
  }

  /**
   * Get merkle tree path.
   *
   * @return merkle tree path
   */
  public InclusionCertificate getInclusionCertificate() {
    return this.inclusionCertificate;
  }

  /**
   * Get unicity certificate.
   *
   * @return unicity certificate
   */
  public UnicityCertificate getUnicityCertificate() {
    return this.unicityCertificate;
  }

  /**
   * Get certification data on inclusion proof, null on non inclusion proof.
   *
   * @return authenticator
   */
  public Optional<CertificationData> getCertificationData() {
    return Optional.ofNullable(this.certificationData);
  }

  /**
   * Deserialize inclusion proof from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return inclusion proof
   */
  public static InclusionProof fromCbor(byte[] bytes) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != InclusionProof.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData());

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != InclusionProof.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return new InclusionProof(
            CborDeserializer.decodeNullable(data.get(1), CertificationData::fromCbor),
            CborDeserializer.decodeNullable(data.get(2), (inclusionCertificate) ->
                    InclusionCertificate.decode(CborDeserializer.decodeByteString(inclusionCertificate))
            ),
            UnicityCertificate.fromCbor(data.get(3))
    );
  }

  /**
   * Serialize inclusion proof to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
            InclusionProof.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(InclusionProof.VERSION),
                    CborSerializer.encodeOptional(this.certificationData, CertificationData::toCbor),
                    CborSerializer.encodeOptional(this.inclusionCertificate, (inclusionCertificate) ->
                            CborSerializer.encodeByteString(inclusionCertificate.encode())
                    ),
                    this.unicityCertificate.toCbor()
            )
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof InclusionProof)) {
      return false;
    }
    InclusionProof that = (InclusionProof) o;
    return Objects.equals(this.inclusionCertificate, that.inclusionCertificate) && Objects.equals(
            this.certificationData,
            that.certificationData);
  }

  @Override
  public int hashCode() {
    return Objects.hash(InclusionProof.VERSION, this.inclusionCertificate, this.certificationData);
  }

  @Override
  public String toString() {
    return String.format(
            "InclusionProof{certificationData=%s, inclusionCertificate=%s, unicityCertificate=%s}",
            this.inclusionCertificate,
            this.certificationData, this.unicityCertificate);
  }
}
