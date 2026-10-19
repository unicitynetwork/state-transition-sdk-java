package org.unicitylabs.sdk.api;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/**
 * Unverified material that claims the absence of a state at a snapshot. It is not evidence until a
 * non-inclusion verification accepts it.
 *
 * <p>This is the transport-level holder of the shared interfaces. {@link #getEncoded()} is the
 * <em>complete</em> {@code get_non_inclusion_proof.v1} result exactly as the service sent it
 * (hex-decoded), the wrapper {@code [blockNumber, 39034([1, certificate, unicityCertificate])]}
 * included: wrapper-level malformation is a rejection of the verification, so the holder never
 * splits or normalizes the wrapper. The canonical decoding, the certificate model and the
 * verification belong to the non-inclusion verifier. An HTTP adapter passes the decoded result
 * bytes unchanged.
 */
public final class NonInclusionProof {

  private final StateId stateId;
  private final byte[] encoded;

  /**
   * Creates the holder.
   *
   * @param stateId the state the proof was requested for; bound locally, the wire does not carry it
   * @param encoded the proof bytes as the service sent them
   */
  public NonInclusionProof(StateId stateId, byte[] encoded) {
    this.stateId = Objects.requireNonNull(stateId, "stateId cannot be null");
    Objects.requireNonNull(encoded, "encoded cannot be null");
    this.encoded = Arrays.copyOf(encoded, encoded.length);
  }

  /** CBOR tag of the non-inclusion proof. */
  public static final long CBOR_TAG = 39034;
  private static final int VERSION = 1;

  /** The decoded content of a proof, still unverified. */
  static final class Decoded {
    final NonInclusionCertificate certificate;
    final UnicityCertificate unicityCertificate;

    Decoded(NonInclusionCertificate certificate, UnicityCertificate unicityCertificate) {
      this.certificate = certificate;
      this.unicityCertificate = unicityCertificate;
    }
  }

  /**
   * Decodes the proof in the exact profile.
   *
   * @return the content
   * @throws CborSerializationException if the bytes deviate from the profile
   */
  Decoded decode() {
    List<byte[]> wrapper = CborDeserializer.decodeArray(encoded, 2);
    CborDeserializer.decodeUnsignedInteger(wrapper.get(0)); // block number: unauthenticated
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(wrapper.get(1));
    if (tag.getTag() != CBOR_TAG) {
      throw new CborSerializationException("Invalid non-inclusion proof tag.");
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), 3);
    if (CborDeserializer.decodeUnsignedInteger(data.get(0)).asLong() != VERSION) {
      throw new CborSerializationException("Unsupported non-inclusion proof version.");
    }
    NonInclusionCertificate certificate;
    try {
      certificate = NonInclusionCertificate.decode(CborDeserializer.decodeByteString(data.get(1)));
    } catch (IllegalArgumentException e) {
      throw new CborSerializationException("Invalid non-inclusion certificate: " + e.getMessage());
    }
    UnicityCertificate uc = UnicityCertificate.fromCbor(data.get(2));
    if (!Arrays.equals(uc.toCbor(), data.get(2))) {
      throw new CborSerializationException("Unicity certificate is not canonical.");
    }
    return new Decoded(certificate, uc);
  }

  /**
   * The queried state.
   *
   * @return state id
   */
  public StateId getStateId() {
    return stateId;
  }

  /**
   * The complete {@code get_non_inclusion_proof.v1} result as received.
   *
   * @return copy of the bytes
   */
  public byte[] getEncoded() {
    return Arrays.copyOf(encoded, encoded.length);
  }
}
