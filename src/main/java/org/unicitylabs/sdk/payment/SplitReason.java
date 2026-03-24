package org.unicitylabs.sdk.payment;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Token;

public class SplitReason {

  private final Token token;
  private final List<SplitReasonProof> proofs;

  private SplitReason(
      Token token,
      List<SplitReasonProof> proofs
  ) {
    this.token = token;
    this.proofs = List.copyOf(proofs);
  }

  public Token getToken() {
    return this.token;
  }

  public List<SplitReasonProof> getProofs() {
    return this.proofs;
  }

  public static SplitReason create(Token token, List<SplitReasonProof> proofs) {
    Objects.requireNonNull(token, "token cannot be null");
    Objects.requireNonNull(proofs, "proofs cannot be null");

    if (proofs.size() == 0) {
      throw new IllegalArgumentException("proofs cannot be empty");
    }

    return new SplitReason(token, proofs);
  }

  public static SplitReason fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new SplitReason(
        Token.fromCbor(data.get(0)),
        CborDeserializer.decodeArray(data.get(1)).stream().map(SplitReasonProof::fromCbor).collect(Collectors.toList())
    );
  }

  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        this.token.toCbor(),
        CborSerializer.encodeArray(this.proofs.stream().map(SplitReasonProof::toCbor).toArray(byte[][]::new))
    );
  }
}
