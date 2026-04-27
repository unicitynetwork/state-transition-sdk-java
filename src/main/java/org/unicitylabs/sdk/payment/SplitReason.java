package org.unicitylabs.sdk.payment;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Token;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The reason for token splitting represented by an input token and inclusion proofs.
 */
public final class SplitReason {

  private final Token token;
  private final List<SplitReasonProof> proofs;

  private SplitReason(
          Token token,
          List<SplitReasonProof> proofs
  ) {
    this.token = token;
    this.proofs = List.copyOf(proofs);
  }

  /**
   * Get the token being split.
   *
   * @return token
   */
  public Token getToken() {
    return this.token;
  }

  /**
   * Get proofs supporting the split reason.
   *
   * @return proof list
   */
  public List<SplitReasonProof> getProofs() {
    return this.proofs;
  }

  /**
   * Create a split reason.
   *
   * @param token token being split
   * @param proofs proofs supporting split eligibility
   *
   * @return split reason
   */
  public static SplitReason create(Token token, List<SplitReasonProof> proofs) {
    Objects.requireNonNull(token, "token cannot be null");
    Objects.requireNonNull(proofs, "proofs cannot be null");

    if (proofs.isEmpty()) {
      throw new IllegalArgumentException("proofs cannot be empty");
    }

    return new SplitReason(token, proofs);
  }

  /**
   * Deserialize split reason from CBOR bytes.
   *
   * @param bytes CBOR bytes
   *
   * @return split reason
   */
  public static SplitReason fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new SplitReason(
            Token.fromCbor(data.get(0)),
            CborDeserializer.decodeArray(data.get(1)).stream().map(SplitReasonProof::fromCbor).collect(Collectors.toList())
    );
  }

  /**
   * Serialize split reason to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
            this.token.toCbor(),
            CborSerializer.encodeArray(this.proofs.stream().map(SplitReasonProof::toCbor).toArray(byte[][]::new))
    );
  }
}
