package org.unicitylabs.sdk.unicityid;

import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.EncodedPredicate;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.MintTransactionState;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.Transaction;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Mint transaction that derives its token id from a {@link UnicityId}. The token's data field is
 * the encoded target predicate.
 */
public final class UnicityIdMintTransaction implements Transaction {
  public static final long CBOR_TAG = 39041;
  private static final int VERSION = 1;

  private final MintTransactionState sourceStateHash;
  private final EncodedPredicate lockScript;
  private final EncodedPredicate recipient;
  private final TokenId tokenId;
  private final TokenType tokenType;
  private final SignaturePredicate targetPredicate;
  private final UnicityId unicityId;

  private UnicityIdMintTransaction(
          MintTransactionState sourceStateHash,
          EncodedPredicate lockScript,
          EncodedPredicate recipient,
          TokenId tokenId,
          TokenType tokenType,
          SignaturePredicate targetPredicate,
          UnicityId unicityId
  ) {
    this.sourceStateHash = sourceStateHash;
    this.lockScript = lockScript;
    this.recipient = recipient;
    this.tokenId = tokenId;
    this.tokenType = tokenType;
    this.targetPredicate = targetPredicate;
    this.unicityId = unicityId;
  }

  /**
   * Get the version number.
   *
   * @return version
   */
  public int getVersion() {
    return UnicityIdMintTransaction.VERSION;
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
   * Get the token id derived from the unicity id.
   *
   * @return token id
   */
  public TokenId getTokenId() {
    return this.tokenId;
  }

  /**
   * Get the token type.
   *
   * @return token type
   */
  public TokenType getTokenType() {
    return this.tokenType;
  }

  /**
   * Get the target predicate (the predicate the minted token is locked to).
   *
   * @return target predicate
   */
  public SignaturePredicate getTargetPredicate() {
    return this.targetPredicate;
  }

  /**
   * Get the unicity id.
   *
   * @return unicity id
   */
  public UnicityId getUnicityId() {
    return this.unicityId;
  }

  @Override
  public Optional<byte[]> getData() {
    return Optional.of(EncodedPredicate.fromPredicate(this.targetPredicate).toCbor());
  }

  @Override
  public byte[] getStateMask() {
    return this.tokenId.getBytes();
  }

  /**
   * Create a unicity id mint transaction. The token id is derived from the unicity id; the lock
   * script is supplied by the caller.
   *
   * @param lockScript lock script predicate (the predicate that must be unlocked to spend this
   *     transaction)
   * @param recipient recipient predicate
   * @param unicityId unicity id producing the token id
   * @param tokenType token type identifier
   * @param targetPredicate target predicate the minted token will be locked to
   *
   * @return mint transaction
   */
  public static UnicityIdMintTransaction create(
          SignaturePredicate lockScript,
          Predicate recipient,
          UnicityId unicityId,
          TokenType tokenType,
          SignaturePredicate targetPredicate
  ) {
    Objects.requireNonNull(lockScript, "lockScript cannot be null");
    Objects.requireNonNull(recipient, "recipient cannot be null");
    Objects.requireNonNull(unicityId, "unicityId cannot be null");
    Objects.requireNonNull(tokenType, "tokenType cannot be null");
    Objects.requireNonNull(targetPredicate, "targetPredicate cannot be null");

    TokenId tokenId = unicityId.toTokenId();

    return new UnicityIdMintTransaction(
            MintTransactionState.create(tokenId),
            EncodedPredicate.fromPredicate(lockScript),
            EncodedPredicate.fromPredicate(recipient),
            tokenId,
            tokenType,
            targetPredicate,
            unicityId
    );
  }

  /**
   * Deserialize a unicity id mint transaction from CBOR bytes.
   *
   * @param bytes CBOR bytes
   *
   * @return mint transaction
   *
   * @throws CborSerializationException if the bytes do not carry the expected tag, version, or if
   *     the encoded token id does not match the unicity id
   */
  public static UnicityIdMintTransaction fromCbor(byte[] bytes) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != UnicityIdMintTransaction.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), 6);

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != UnicityIdMintTransaction.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return UnicityIdMintTransaction.create(
            SignaturePredicate.fromPredicate(
                    EncodedPredicate.fromCbor(data.get(1))
            ),
            EncodedPredicate.fromCbor(data.get(2)),
            UnicityId.fromCbor(data.get(3)),
            TokenType.fromCbor(data.get(4)),
            SignaturePredicate.fromPredicate(
                    EncodedPredicate.fromCbor(data.get(5))
            )
    );
  }

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

  @Override
  public DataHash calculateTransactionHash() {
    return new DataHasher(HashAlgorithm.SHA256).update(this.toCbor()).digest();
  }

  @Override
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
            UnicityIdMintTransaction.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(UnicityIdMintTransaction.VERSION),
                    this.lockScript.toCbor(),
                    this.recipient.toCbor(),
                    this.unicityId.toCbor(),
                    this.tokenType.toCbor(),
                    EncodedPredicate.fromPredicate(this.targetPredicate).toCbor()
            )
    );
  }

  /**
   * Build the certified version by attaching and verifying an inclusion proof.
   *
   * @param trustBase root trust base
   * @param predicateVerifier predicate verifier
   * @param inclusionProof inclusion proof
   *
   * @return certified mint transaction
   */
  public CertifiedUnicityIdMintTransaction toCertifiedTransaction(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          InclusionProof inclusionProof
  ) {
    return CertifiedUnicityIdMintTransaction.fromTransaction(trustBase, predicateVerifier, this,
            inclusionProof);
  }

  @Override
  public String toString() {
    return String.format(
            "UnicityIdMintTransaction{lockScript=%s, recipient=%s, tokenId=%s, tokenType=%s, unicityId=%s, targetPredicate=%s}",
            this.lockScript, this.recipient, this.tokenId, this.tokenType, this.unicityId,
            this.targetPredicate
    );
  }
}
