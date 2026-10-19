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
  static final int VERSION = 1;

  private final InclusionCertificate inclusionCertificate;
  private final CertificationData certificationData;
  private final long referenceTime;
  private final UnicityCertificate unicityCertificate;

  /**
   * An InclusionProof describes a certified leaf, so every field is present. The aggregator's
   * answer for a state it has not certified yet is not an InclusionProof at all — see
   * {@link InclusionProofResponse}, which is the type that can express it.
   */
  InclusionProof(
          CertificationData certificationData,
          long referenceTime,
          InclusionCertificate inclusionCertificate,
          UnicityCertificate unicityCertificate
  ) {
    Objects.requireNonNull(certificationData, "Certification data cannot be null.");
    Objects.requireNonNull(inclusionCertificate, "Inclusion certificate cannot be null.");
    Objects.requireNonNull(unicityCertificate, "Unicity certificate cannot be null.");

    this.inclusionCertificate = inclusionCertificate;
    this.certificationData = certificationData;
    this.referenceTime = referenceTime;
    this.unicityCertificate = unicityCertificate;
  }

  public int getVersion() {
    return VERSION;
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
   * The same certified leaf under another unicity certificate: certification data, reference time
   * and inclusion path are kept byte for byte, only the certificate that commits to the root is
   * replaced. This is how a proof is re-anchored under a later seal; the result is not verified
   * here and must be verified like any other proof.
   *
   * @param replacement the certificate the proof is re-anchored under
   * @return a new proof
   */
  public InclusionProof withUnicityCertificate(UnicityCertificate replacement) {
    return new InclusionProof(this.certificationData, this.referenceTime,
            this.inclusionCertificate, Objects.requireNonNull(replacement,
            "replacement cannot be null"));
  }

  /**
   * Get certification data of the certified leaf.
   *
   * @return certification data
   */
  public CertificationData getCertificationData() {
    return this.certificationData;
  }

  /**
   * Get the reference time of the round the certified leaf was created in, in Unix seconds.
   *
   * <p>It cannot be recovered from the certificate chain: an aggregator serves proofs against the
   * current certified root, whose input record time is that of the latest round rather than the
   * one the leaf was created under.
   *
   * @return reference time
   */
  public long getReferenceTime() {
    return this.referenceTime;
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
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), 5);

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    CertificationData certificationData =
            CborDeserializer.decodeNullable(data.get(1), CertificationData::fromCbor);
    Long referenceTime = CborDeserializer.decodeNullable(data.get(2), value ->
            CborDeserializer.decodeUnsignedInteger(value).asLong());
    InclusionCertificate inclusionCertificate =
            CborDeserializer.decodeNullable(data.get(3), (certificate) ->
                    InclusionCertificate.decode(CborDeserializer.decodeByteString(certificate)));
    if (certificationData == null || referenceTime == null || inclusionCertificate == null) {
      throw new CborSerializationException(
              "Expected a certified leaf, but the inclusion proof describes none.");
    }

    return new InclusionProof(
            certificationData,
            referenceTime,
            inclusionCertificate,
            UnicityCertificate.fromCbor(data.get(4))
    );
  }

  /**
   * Serialize inclusion proof to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    byte[] payload = CborSerializer.encodeArray(CborSerializer.encodeUnsignedInteger(VERSION),
            this.certificationData.toCbor(),
            CborSerializer.encodeUnsignedInteger(this.referenceTime),
            CborSerializer.encodeByteString(this.inclusionCertificate.encode()),
            this.unicityCertificate.toCbor());
    return CborSerializer.encodeTag(
            InclusionProof.CBOR_TAG,
            payload
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof InclusionProof)) {
      return false;
    }
    InclusionProof that = (InclusionProof) o;
    return Objects.equals(this.inclusionCertificate, that.inclusionCertificate) && Objects.equals(this.certificationData, that.certificationData) && Objects.equals(this.referenceTime, that.referenceTime) && Objects.equals(this.unicityCertificate, that.unicityCertificate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.inclusionCertificate, this.certificationData, this.referenceTime, this.unicityCertificate);
  }

  @Override
  public String toString() {
    return String.format(
            "InclusionProof{certificationData=%s, referenceTime=%s, inclusionCertificate=%s, unicityCertificate=%s}",
            this.certificationData,
            this.referenceTime,
            this.inclusionCertificate,
            this.unicityCertificate
    );
  }
}
