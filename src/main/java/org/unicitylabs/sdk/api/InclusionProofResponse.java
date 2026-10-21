package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * What the aggregator answers when asked about a state.
 *
 * <p>This is the wire shape, and it has two forms: a certified leaf, or the absence of one.
 * Keeping that distinction here rather than inside {@link InclusionProof} is what lets the proof
 * itself be complete by construction — a verifier holding one never has to ask whether it
 * describes a leaf.
 */
public class InclusionProofResponse {

  private final long blockNumber;
  private final InclusionProof inclusionProof;
  private final UnicityCertificate unicityCertificate;
  private final ProofStatusHint statusHint;
  private final Long waitedMillis;

  private InclusionProofResponse(
          long blockNumber,
          InclusionProof inclusionProof,
          UnicityCertificate unicityCertificate,
          ProofStatusHint statusHint,
          Long waitedMillis
  ) {
    this.blockNumber = blockNumber;
    this.inclusionProof = inclusionProof;
    this.unicityCertificate = unicityCertificate;
    this.statusHint = statusHint;
    this.waitedMillis = waitedMillis;
  }

  /**
   * The aggregator has certified this state.
   *
   * <p>The round it was served against is the proof's own, so there is no second certificate to
   * supply and none that could disagree with it.
   *
   * @param blockNumber block number the answer was served at
   * @param inclusionProof the certified leaf
   * @return the response
   */
  public static InclusionProofResponse certified(long blockNumber, InclusionProof inclusionProof) {
    return new InclusionProofResponse(blockNumber, inclusionProof,
            inclusionProof.getUnicityCertificate(), null, null);
  }

  /**
   * The aggregator has not certified this state yet, so only the round is meaningful.
   *
   * @param blockNumber block number the answer was served at
   * @param unicityCertificate certificate of the round the answer was served against
   * @return the response
   */
  public static InclusionProofResponse notCertified(long blockNumber,
          UnicityCertificate unicityCertificate) {
    return new InclusionProofResponse(blockNumber, null, unicityCertificate, null, null);
  }

  /**
   * Copy of this response with the status hint the aggregator attached.
   *
   * @param statusHint unauthenticated hint, null if none or not recognized
   * @return the response
   */
  public InclusionProofResponse withStatusHint(ProofStatusHint statusHint) {
    return new InclusionProofResponse(blockNumber, inclusionProof, unicityCertificate, statusHint,
            waitedMillis);
  }

  /**
   * Copy of this response with the aggregator's echo of the time it waited.
   *
   * @param waitedMillis unauthenticated echo, null if none
   * @return the response
   */
  public InclusionProofResponse withWaitedMillis(Long waitedMillis) {
    return new InclusionProofResponse(blockNumber, inclusionProof, unicityCertificate, statusHint,
            waitedMillis);
  }

  /**
   * Unauthenticated status hint of the aggregator. Control flow only, never evidence.
   *
   * @return hint, null if the aggregator sent none or one this version does not know
   */
  public ProofStatusHint getStatusHint() {
    return this.statusHint;
  }

  /**
   * Unauthenticated echo of the time the aggregator held the request. Never extends a deadline.
   *
   * @return milliseconds, null if the request asked for no wait or the aggregator sent no echo
   */
  public Long getWaitedMillis() {
    return this.waitedMillis;
  }

  /**
   * Get the block number the answer was served at, as unsigned 64-bit bits. Unauthenticated
   * transport metadata, never evidence.
   *
   * @return block number
   */
  public long getBlockNumber() {
    return this.blockNumber;
  }

  /**
   * Get the certificate of the round this answer was served against. Present either way.
   *
   * @return unicity certificate
   */
  public UnicityCertificate getUnicityCertificate() {
    return this.unicityCertificate;
  }

  /**
   * Get the certified leaf, or null when the state is not certified yet.
   *
   * @return inclusion proof, or null
   */
  public InclusionProof getInclusionProof() {
    return this.inclusionProof;
  }

  /**
   * Deserialize response from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return inclusion proof response
   */
  public static InclusionProofResponse fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes, 2);
    long blockNumber = CborDeserializer.decodeUnsignedInteger(data.get(0)).asLong();

    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(data.get(1));
    if (tag.getTag() != InclusionProof.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> proof = CborDeserializer.decodeArray(tag.getData(), 5);
    int version = CborDeserializer.decodeUnsignedInteger(proof.get(0)).asInt();
    if (version != InclusionProof.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    CertificationData certificationData =
            CborDeserializer.decodeNullable(proof.get(1), CertificationData::fromCbor);
    Long referenceTime = CborDeserializer.decodeNullable(proof.get(2), value ->
            CborDeserializer.decodeUnsignedInteger(value).asLong());
    InclusionCertificate inclusionCertificate =
            CborDeserializer.decodeNullable(proof.get(3), (certificate) ->
                    InclusionCertificate.decode(CborDeserializer.decodeByteString(certificate)));
    UnicityCertificate unicityCertificate = UnicityCertificate.fromCbor(proof.get(4));

    // The three leaf fields travel together: all present once the request has been included in a
    // certified round, all absent while it is still pending. Anything in between is a protocol
    // violation, and rejecting it here is what lets InclusionProof require all three.
    long present = Stream.of(certificationData, referenceTime, inclusionCertificate)
            .filter(Objects::nonNull)
            .count();
    if (present == 0) {
      return InclusionProofResponse.notCertified(blockNumber, unicityCertificate);
    }
    if (present != 3) {
      throw new CborSerializationException(
              "InclusionProof must carry certification data, reference time and inclusion "
                      + "certificate together, or none of them.");
    }

    return InclusionProofResponse.certified(blockNumber, new InclusionProof(certificationData,
            referenceTime, inclusionCertificate, unicityCertificate));
  }

  /**
   * Serialize inclusion proof response to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
            CborSerializer.encodeUnsignedInteger(this.blockNumber),
            this.inclusionProof == null
                    ? this.encodeNoCertifiedLeaf()
                    : this.inclusionProof.toCbor()
    );
  }

  /**
   * Encode the wire form for a state with no certified leaf: the three leaf fields absent, the
   * round's certificate still present.
   *
   * @return CBOR bytes
   */
  private byte[] encodeNoCertifiedLeaf() {
    return CborSerializer.encodeTag(
            InclusionProof.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(InclusionProof.VERSION),
                    CborSerializer.encodeNull(),
                    CborSerializer.encodeNull(),
                    CborSerializer.encodeNull(),
                    this.unicityCertificate.toCbor()));
  }
}
