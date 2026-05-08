package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.MintSigningService;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


/**
 * Represents a Mint Transaction.
 *
 * <p>This transaction is responsible for minting new tokens with specific attributes and assigns
 * it to an initial owner.
 */
public class MintTransaction implements Transaction {
  public static final long CBOR_TAG = 39041;
  private static final int VERSION = 1;

  private final MintTransactionState sourceStateHash;
  private final EncodedPredicate lockScript;
  private final EncodedPredicate recipient;
  private final TokenId tokenId;
  private final TokenType tokenType;
  private final byte[] justification;
  private final byte[] data;

  private MintTransaction(
          MintTransactionState sourceStateHash,
          EncodedPredicate lockScript,
          EncodedPredicate recipient,
          TokenId tokenId,
          TokenType tokenType,
          byte[] justification,
          byte[] data
  ) {
    this.sourceStateHash = sourceStateHash;
    this.lockScript = lockScript;
    this.recipient = recipient;
    this.tokenId = tokenId;
    this.tokenType = tokenType;
    this.justification = justification;
    this.data = data;
  }

  public int getVersion() {
    return MintTransaction.VERSION;
  }


  @Override
  public MintTransactionState getSourceStateHash() {
    return this.sourceStateHash;
  }

  @Override
  public EncodedPredicate getLockScript() {
    return this.lockScript;
  }

  @Override
  public EncodedPredicate getRecipient() {
    return this.recipient;
  }

  /**
   * Retrieves the unique token identifier.
   *
   * @return the token identifier as a {@code TokenId}.
   */
  public TokenId getTokenId() {
    return this.tokenId;
  }

  /**
   * Retrieves the type identifier of the token.
   *
   * @return the token type as a {@code TokenType}.
   */
  public TokenType getTokenType() {
    return this.tokenType;
  }

  /**
   * Retrieves the justification for the mint transaction, if any.
   *
   * @return optional justification bytes
   */
  public Optional<byte[]> getJustification() {
    return Optional.ofNullable(this.justification != null ? Arrays.copyOf(this.justification, this.justification.length) : null);
  }

  @Override
  public Optional<byte[]> getData() {
    return Optional.ofNullable(this.data != null ? Arrays.copyOf(this.data, this.data.length) : null);
  }

  @Override
  public byte[] getStateMask() {
    return this.tokenId.getBytes();
  }

  /**
   * Create a mint transaction.
   *
   * @param recipient recipient predicate
   * @param tokenId token identifier
   * @param tokenType token type identifier
   * @param justification mint justification bytes, may be null
   * @param data payload bytes, may be null
   *
   * @return mint transaction
   */
  public static MintTransaction create(
          Predicate recipient,
          TokenId tokenId,
          TokenType tokenType,
          byte[] justification,
          byte[] data
  ) {
    Objects.requireNonNull(recipient, "Recipient cannot be null");
    Objects.requireNonNull(tokenId, "Token ID cannot be null");
    Objects.requireNonNull(tokenType, "Token type cannot be null");

    SigningService signingService = MintSigningService.create(tokenId);
    return new MintTransaction(
            MintTransactionState.create(tokenId),
            EncodedPredicate.fromPredicate(SignaturePredicate.fromSigningService(signingService)),
            EncodedPredicate.fromPredicate(recipient),
            tokenId,
            tokenType,
            justification != null ? Arrays.copyOf(justification, justification.length) : null,
            data != null ? Arrays.copyOf(data, data.length) : null
    );
  }

  /**
   * Deserialize mint transaction from CBOR bytes.
   *
   * @param bytes CBOR bytes
   *
   * @return mint transaction
   */
  public static MintTransaction fromCbor(byte[] bytes) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != MintTransaction.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), 6);

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != MintTransaction.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return MintTransaction.create(
            EncodedPredicate.fromCbor(data.get(1)),
            TokenId.fromCbor(data.get(2)),
            TokenType.fromCbor(data.get(3)),
            CborDeserializer.decodeNullable(data.get(4), CborDeserializer::decodeByteString),
            CborDeserializer.decodeNullable(data.get(5), CborDeserializer::decodeByteString)
    );
  }

  /**
   * Calculate mint transaction state hash.
   *
   * @return state hash
   */
  @Override
  public DataHash calculateStateHash() {
    return new DataHasher(HashAlgorithm.SHA256)
            .update(
                    CborSerializer.encodeArray(
                            CborSerializer.encodeByteString(this.sourceStateHash.getImprint()),
                            CborSerializer.encodeByteString(this.getStateMask())
                    )
            )
            .digest();
  }

  /**
   * Calculate hash of serialized mint transaction.
   *
   * @return transaction hash
   */
  @Override
  public DataHash calculateTransactionHash() {
    return new DataHasher(HashAlgorithm.SHA256).update(this.toCbor()).digest();
  }

  /**
   * Serialize mint transaction to CBOR bytes.
   *
   * @return CBOR bytes
   */
  @Override
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
            MintTransaction.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(MintTransaction.VERSION),
                    this.recipient.toCbor(),
                    this.tokenId.toCbor(),
                    this.tokenType.toCbor(),
                    CborSerializer.encodeNullable(this.justification, CborSerializer::encodeByteString),
                    CborSerializer.encodeNullable(this.data, CborSerializer::encodeByteString)
            )
    );
  }

  /**
   * Build certified mint transaction by attaching and verifying inclusion proof.
   *
   * @param trustBase root trust base
   * @param predicateVerifier predicate verifier
   * @param inclusionProof inclusion proof
   *
   * @return certified mint transaction
   */
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
