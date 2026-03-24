package org.unicitylabs.sdk.transaction;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.MintSigningService;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

public class MintTransaction implements Transaction {

  private final MintTransactionState sourceStateHash;
  private final Predicate lockScript;
  private final Address recipient;
  private final TokenId tokenId;
  private final TokenType tokenType;
  private final byte[] data;

  private MintTransaction(
      MintTransactionState sourceStateHash,
      Predicate lockScript,
      Address recipient,
      TokenId tokenId,
      TokenType tokenType,
      byte[] data
  ) {
    this.sourceStateHash = sourceStateHash;
    this.lockScript = lockScript;
    this.recipient = recipient;
    this.tokenId = tokenId;
    this.tokenType = tokenType;
    this.data = data;
  }

  public MintTransactionState getSourceStateHash() {
    return this.sourceStateHash;
  }

  public Predicate getLockScript() {
    return this.lockScript;
  }

  public Address getRecipient() {
    return this.recipient;
  }

  public TokenId getTokenId() {
    return this.tokenId;
  }

  public TokenType getTokenType() {
    return this.tokenType;
  }

  @Override
  public byte[] getData() {
    return this.data;
  }

  @Override
  public byte[] getX() {
    return this.tokenId.getBytes();
  }

  public static MintTransaction create(
      Address recipient,
      TokenId tokenId,
      TokenType tokenType,
      byte[] data
  ) {
    Objects.requireNonNull(recipient, "Recipient cannot be null");
    Objects.requireNonNull(tokenId, "Token ID cannot be null");
    Objects.requireNonNull(tokenType, "Token type cannot be null");
    Objects.requireNonNull(data, "Data cannot be null");

    SigningService signingService = MintSigningService.create(tokenId);
    return new MintTransaction(
        MintTransactionState.create(tokenId),
        PayToPublicKeyPredicate.fromSigningService(signingService),
        recipient,
        tokenId,
        tokenType,
        Arrays.copyOf(data, data.length)
    );
  }

  public static MintTransaction fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);
    List<byte[]> aux = CborDeserializer.decodeArray(data.get(2));

    return MintTransaction.create(
        Address.fromCbor(data.get(0)),
        TokenId.fromCbor(data.get(1)),
        TokenType.fromCbor(aux.get(0)),
        CborDeserializer.decodeByteString(aux.get(1))
    );
  }

  @Override
  public DataHash calculateStateHash() {
    return new DataHasher(HashAlgorithm.SHA256)
        .update(
            CborSerializer.encodeArray(
                CborSerializer.encodeByteString(this.sourceStateHash.getImprint()),
                CborSerializer.encodeByteString(this.getX())
            )
        )
        .digest();
  }

  @Override
  public DataHash calculateTransactionHash() {
    return new DataHasher(HashAlgorithm.SHA256).update(this.toCbor()).digest();
  }

  @Override
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        this.recipient.toCbor(),
        this.tokenId.toCbor(),
        CborSerializer.encodeArray(this.tokenType.toCbor(),
            CborSerializer.encodeByteString(this.data))
    );
  }

  public CertifiedMintTransaction toCertifiedTransaction(
      RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier,
      InclusionProof inclusionProof
  ) {
    return CertifiedMintTransaction.fromTransaction(trustBase, predicateVerifier, this,
        inclusionProof);
  }

  @Override
  public String toString() {
    return String.format(
        "MintTransaction{sourceStateHash=%s, lockScript=%s, recipient=%s, tokenId=%s, tokenType=%s, data=%s}",
        this.sourceStateHash, this.lockScript, this.recipient, this.tokenId, this.tokenType,
        HexConverter.encode(this.data));
  }
}
