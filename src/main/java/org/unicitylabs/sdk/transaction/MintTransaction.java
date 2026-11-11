package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
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
  /** The only accepted wire version. One version, one element count. */
  public static final int VERSION = 2;
  private static final int FIELD_COUNT = 8;

  private final MintTransactionState sourceStateHash;
  private final EncodedPredicate lockScript;
  private final NetworkId networkId;
  private final EncodedPredicate recipient;
  private final TokenSalt salt;
  private final TokenType tokenType;
  private final TokenId tokenId;
  private final Long expiresAt;
  private final byte[] justification;
  private final byte[] data;

  private MintTransaction(
          MintTransactionState sourceStateHash,
          EncodedPredicate lockScript,
          NetworkId networkId,
          EncodedPredicate recipient,
          TokenSalt salt,
          TokenType tokenType,
          TokenId tokenId,
          Long expiresAt,
          byte[] justification,
          byte[] data
  ) {
    this.sourceStateHash = sourceStateHash;
    this.lockScript = lockScript;
    this.networkId = networkId;
    this.recipient = recipient;
    this.salt = salt;
    this.tokenType = tokenType;
    this.tokenId = tokenId;
    this.expiresAt = ExpiresAt.validate(expiresAt);
    this.justification = justification;
    this.data = data;
  }

  @Override
  public Optional<Long> getExpiresAt() {
    return Optional.ofNullable(this.expiresAt);
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
   * Retrieves the network identifier.
   *
   * @return the network identifier as a {@code NetworkId}.
   */
  public NetworkId getNetworkId() {
    return this.networkId;
  }

  /**
   * Retrieves the mint-transaction salt.
   *
   * @return the salt as a {@code TokenSalt}.
   */
  public TokenSalt getSalt() {
    return this.salt;
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
  public StateMask getStateMask() {
    return StateMask.fromBytes(this.tokenId.getBytes());
  }

  /**
   * Start building a mint transaction.
   *
   * @param networkId network identifier
   * @param recipient recipient predicate
   *
   * @return mint transaction builder
   */
  public static Builder builder(NetworkId networkId, Predicate recipient) {
    return new Builder(networkId, recipient);
  }

  /**
   * Builds a {@link MintTransaction}. The network id and recipient are required and are supplied
   * to {@link MintTransaction#builder}; everything else is optional and named, so adding a further
   * optional field later does not disturb existing call sites.
   */
  public static final class Builder {

    private final NetworkId networkId;
    private final Predicate recipient;
    private TokenType tokenType;
    private TokenSalt salt;
    private byte[] data;
    private byte[] justification;
    private Long expiresAt;

    private Builder(NetworkId networkId, Predicate recipient) {
      this.networkId = Objects.requireNonNull(networkId, "Network id cannot be null");
      this.recipient = Objects.requireNonNull(recipient, "Recipient cannot be null");
    }

    /**
     * Sets the token type. Defaults to a freshly generated type.
     *
     * @param tokenType token type identifier
     *
     * @return this builder
     */
    public Builder tokenType(TokenType tokenType) {
      this.tokenType = tokenType;
      return this;
    }

    /**
     * Sets the mint-transaction salt. Defaults to a freshly generated salt.
     *
     * @param salt mint-transaction salt
     *
     * @return this builder
     */
    public Builder salt(TokenSalt salt) {
      this.salt = salt;
      return this;
    }

    /**
     * Sets the payload bytes.
     *
     * @param data payload bytes, may be null
     *
     * @return this builder
     */
    public Builder data(byte[] data) {
      this.data = data != null ? Arrays.copyOf(data, data.length) : null;
      return this;
    }

    /**
     * Sets the mint justification bytes.
     *
     * @param justification mint justification bytes, may be null
     *
     * @return this builder
     */
    public Builder justification(byte[] justification) {
      this.justification =
              justification != null ? Arrays.copyOf(justification, justification.length) : null;
      return this;
    }

    /**
     * Sets the exclusive certification request deadline, in Unix seconds.
     *
     * <p>Leave it unset, or pass null, to let the Unicity Service assign a deadline from consensus
     * time. That requires no local clock, and the assigned value is not recorded in the token.
     *
     * @param expiresAt exclusive request deadline, may be null
     *
     * @return this builder
     */
    public Builder expiresAt(Long expiresAt) {
      this.expiresAt = expiresAt;
      return this;
    }

    /**
     * Builds the mint transaction, deriving the token id, lock script and mint state.
     *
     * @return mint transaction
     */
    public MintTransaction build() {
      TokenType type = this.tokenType != null ? this.tokenType : TokenType.generate();
      TokenSalt mintSalt = this.salt != null ? this.salt : TokenSalt.generate();

      TokenId tokenId = TokenId.fromSalt(this.networkId, mintSalt);
      SigningService signingService = MintSigningService.create(tokenId);
      return new MintTransaction(
              MintTransactionState.create(tokenId),
              EncodedPredicate.fromPredicate(SignaturePredicate.fromSigningService(signingService)),
              this.networkId,
              EncodedPredicate.fromPredicate(this.recipient),
              mintSalt,
              type,
              tokenId,
              this.expiresAt,
              this.justification,
              this.data
      );
    }
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
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), FIELD_COUNT);

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return MintTransaction
            .builder(
                    NetworkId.fromId(CborDeserializer.decodeUnsignedInteger(data.get(1)).asShort()),
                    EncodedPredicate.fromCbor(data.get(2)))
            .salt(TokenSalt.fromCbor(data.get(3)))
            .tokenType(TokenType.fromCbor(data.get(4)))
            .justification(
                    CborDeserializer.decodeNullable(data.get(5), CborDeserializer::decodeByteString))
            .data(CborDeserializer.decodeNullable(data.get(6), CborDeserializer::decodeByteString))
            .expiresAt(
                    CborDeserializer.decodeNullable(
                            data.get(7),
                            value -> CborDeserializer.decodeUnsignedInteger(value).asLong()))
            .build();
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
                            this.getStateMask().toCbor()
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
                    CborSerializer.encodeUnsignedInteger(VERSION),
                    CborSerializer.encodeUnsignedInteger(this.networkId.getId()),
                    this.recipient.toCbor(),
                    this.salt.toCbor(),
                    this.tokenType.toCbor(),
                    CborSerializer.encodeNullable(this.justification, CborSerializer::encodeByteString),
                    CborSerializer.encodeNullable(this.data, CborSerializer::encodeByteString),
                    CborSerializer.encodeNullable(this.expiresAt, CborSerializer::encodeUnsignedInteger))
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
          TrustBaseHistoryView trustBase,
          PredicateVerifierService predicateVerifier,
          InclusionProof inclusionProof
  ) {
    return CertifiedMintTransaction.fromTransaction(trustBase, predicateVerifier, this,
            inclusionProof);
  }

  @Override
  public String toString() {
    return String.format(
            "MintTransaction{sourceStateHash=%s, lockScript=%s, networkId=%s, recipient=%s, salt=%s, tokenType=%s, tokenId=%s, expiresAt=%s, data=%s}",
            this.sourceStateHash, this.lockScript, this.networkId, this.recipient, this.salt,
            this.tokenType, this.tokenId, this.expiresAt, HexConverter.encode(this.data));
  }
}
