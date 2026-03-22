package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/**
 * Inclusion proof response.
 */
public class InclusionProofResponse {

  private final long blockNumber;
  private final InclusionProof inclusionProof;

  /**
   * Create inclison proof response.
   *
   * @param inclusionProof inclusion proof
   */
  InclusionProofResponse(
      long blockNumber,
      InclusionProof inclusionProof
  ) {
    this.blockNumber = blockNumber;
    this.inclusionProof = inclusionProof;
  }

  /**
   * Get inclusion proof.
   *
   * @return inclusion proof
   */
  public InclusionProof getInclusionProof() {
    return this.inclusionProof;
  }

  /**
   * Create response from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return inclusion proof response
   */
  public static InclusionProofResponse fromCbor(byte[] bytes) {
    var data = CborDeserializer.decodeArray(bytes);
    return new InclusionProofResponse(
        CborDeserializer.decodeUnsignedInteger(data.get(0)).asLong(),
        InclusionProof.fromCbor(data.get(1))
    );
  }

  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        CborSerializer.encodeUnsignedInteger(this.blockNumber),
        this.inclusionProof.toCbor()
    );
  }

}
