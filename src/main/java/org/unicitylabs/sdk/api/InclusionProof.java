package org.unicitylabs.sdk.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTreePath;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/**
 * Represents a proof of inclusion or non-inclusion in a sparse merkle tree.
 */
public class InclusionProof {

  private final SparseMerkleTreePath merkleTreePath;
  private final CertificationData certificationData;
  private final UnicityCertificate unicityCertificate;

  InclusionProof(
      SparseMerkleTreePath merkleTreePath,
      CertificationData certificationData,
      UnicityCertificate unicityCertificate
  ) {
    Objects.requireNonNull(merkleTreePath, "Merkle tree path cannot be null.");
    Objects.requireNonNull(unicityCertificate, "Unicity certificate cannot be null.");

    this.merkleTreePath = merkleTreePath;
    this.certificationData = certificationData;
    this.unicityCertificate = unicityCertificate;
  }

  /**
   * Get merkle tree path.
   *
   * @return merkle tree path
   */
  public SparseMerkleTreePath getMerkleTreePath() {
    return this.merkleTreePath;
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
   * Create inclusion proof from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return inclusion proof
   */
  public static InclusionProof fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new InclusionProof(
        SparseMerkleTreePath.fromCbor(data.get(1)),
        CborDeserializer.decodeNullable(data.get(0), CertificationData::fromCbor),
        UnicityCertificate.fromCbor(data.get(2))
    );
  }

  /**
   * Convert inclusion proof to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        CborSerializer.encodeOptional(this.certificationData, CertificationData::toCbor),
        this.merkleTreePath.toCbor(),
        this.unicityCertificate.toCbor()
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof InclusionProof)) {
      return false;
    }
    InclusionProof that = (InclusionProof) o;
    return Objects.equals(this.merkleTreePath, that.merkleTreePath) && Objects.equals(
        this.certificationData,
        that.certificationData);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.merkleTreePath, this.certificationData);
  }

  @Override
  public String toString() {
    return String.format(
        "InclusionProof{merkleTreePath=%s, certificationData=%s, unicityCertificate=%s}",
        this.merkleTreePath,
        this.certificationData, this.unicityCertificate);
  }
}
