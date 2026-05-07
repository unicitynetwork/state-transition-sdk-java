package org.unicitylabs.sdk.payment;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Token;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mint justification for a split-output token, carrying the burn token of the source and the
 * inclusion proofs that link each output asset back to the burned source aggregation tree.
 */
public final class SplitMintJustification {
  public static final long CBOR_TAG = 39044;

  private final Token token;
  private final List<SplitAssetProof> proofs;

  private SplitMintJustification(
          Token token,
          List<SplitAssetProof> proofs
  ) {
    this.token = token;
    this.proofs = proofs;
  }

  /**
   * Get the burn token whose split produced this justification.
   *
   * @return burn token
   */
  public Token getToken() {
    return this.token;
  }

  /**
   * Get the inclusion proofs supporting this split mint justification.
   *
   * @return proofs
   */
  public List<SplitAssetProof> getProofs() {
    return this.proofs;
  }

  /**
   * Create a split mint justification.
   *
   * @param token burn token of the source token being split
   * @param proofs inclusion proofs supporting split eligibility
   *
   * @return split mint justification
   */
  public static SplitMintJustification create(Token token, Set<SplitAssetProof> proofs) {
    Objects.requireNonNull(token, "token cannot be null");
    Objects.requireNonNull(proofs, "proofs cannot be null");

    if (proofs.isEmpty()) {
      throw new IllegalArgumentException("proofs cannot be empty");
    }

    return new SplitMintJustification(token, List.copyOf(proofs));
  }

  /**
   * Deserialize split mint justification from CBOR bytes.
   *
   * @param bytes CBOR bytes
   *
   * @return split mint justification
   */
  public static SplitMintJustification fromCbor(byte[] bytes) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != SplitMintJustification.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), 2);
    return SplitMintJustification.create(
            Token.fromCbor(data.get(0)),
            CborDeserializer.decodeArray(data.get(1)).stream().map(SplitAssetProof::fromCbor).collect(Collectors.toSet())
    );
  }

  /**
   * Serialize split mint justification to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
            SplitMintJustification.CBOR_TAG,
            CborSerializer.encodeArray(
                    this.token.toCbor(),
                    CborSerializer.encodeArray(this.proofs.stream().map(SplitAssetProof::toCbor).toArray(byte[][]::new))
            )
    );
  }
}
