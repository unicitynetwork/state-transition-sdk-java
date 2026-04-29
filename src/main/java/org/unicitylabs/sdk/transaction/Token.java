package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.verification.CertifiedMintTransactionVerificationRule;
import org.unicitylabs.sdk.transaction.verification.CertifiedTransferTransactionVerificationRule;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.util.verification.VerificationException;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable token aggregate containing the certified genesis mint transaction and transfer history.
 */
public final class Token {
  public static final long CBOR_TAG = 39040;
  private static final int VERSION = 1;

  private final CertifiedMintTransaction genesis;
  private final List<CertifiedTransferTransaction> transactions;

  private Token(CertifiedMintTransaction genesis, List<CertifiedTransferTransaction> transactions) {
    this.genesis = genesis;
    this.transactions = List.copyOf(transactions);
  }

  private Token(CertifiedMintTransaction genesis) {
    this(genesis, List.of());
  }

  public int getVersion() {
    return Token.VERSION;
  }

  /**
   * Returns the token identifier.
   *
   * @return token id
   */
  public TokenId getId() {
    return this.genesis.getTokenId();
  }

  /**
   * Returns the token type.
   *
   * @return token type
   */
  public TokenType getType() {
    return this.genesis.getTokenType();
  }

  /**
   * Returns the certified genesis mint transaction.
   *
   * @return genesis transaction
   */
  public CertifiedMintTransaction getGenesis() {
    return this.genesis;
  }

  /**
   * Returns the most recent transaction in the token history.
   *
   * @return latest transfer transaction, or genesis transaction when no transfers exist
   */
  public Transaction getLatestTransaction() {
    if (this.transactions.isEmpty()) {
      return this.genesis;
    }

    return this.transactions.get(this.transactions.size() - 1);
  }

  /**
   * Returns the certified transfer transactions.
   *
   * @return immutable list of transfer transactions
   */
  public List<CertifiedTransferTransaction> getTransactions() {
    return this.transactions;
  }

  /**
   * Deserializes a token from CBOR.
   *
   * @param bytes CBOR-encoded token bytes
   * @return decoded token
   */
  public static Token fromCbor(byte[] bytes) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != Token.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), 3);

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != Token.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    CertifiedMintTransaction genesis = CertifiedMintTransaction.fromCbor(data.get(1));
    List<byte[]> transactionsCbor = CborDeserializer.decodeArray(data.get(2));

    List<CertifiedTransferTransaction> transactions = new ArrayList<>();
    for (byte[] transaction : transactionsCbor) {
      transactions.add(CertifiedTransferTransaction.fromCbor(transaction, new Token(genesis, transactions)));
    }

    return new Token(genesis, transactions);
  }

  /**
   * Creates a token from a certified genesis transaction and verifies it.
   *
   * @param trustBase trust base used for certification checks
   * @param predicateVerifier predicate verifier service
   * @param mintJustificationVerifier mint justification verifier service
   * @param genesis certified mint transaction
   * @return verified token instance
   * @throws VerificationException if genesis verification fails
   */
  public static Token mint(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          MintJustificationVerifierService mintJustificationVerifier,
          CertifiedMintTransaction genesis
  ) {
    Token token = new Token(genesis);
    VerificationResult<VerificationStatus> result = token.verify(trustBase, predicateVerifier, mintJustificationVerifier);
    if (result.getStatus() != VerificationStatus.OK) {
      throw new VerificationException("Invalid token genesis", result);
    }

    return token;
  }

  /**
   * Returns a new token instance with an additional verified transfer transaction.
   *
   * @param trustBase trust base used for certification checks
   * @param predicateVerifier predicate verifier service
   * @param transaction certified transfer transaction to append
   * @return new token instance with appended transfer
   * @throws VerificationException if transfer verification fails
   */
  public Token transfer(RootTrustBase trustBase, PredicateVerifierService predicateVerifier,
                        CertifiedTransferTransaction transaction) {
    VerificationResult<VerificationStatus> result = CertifiedTransferTransactionVerificationRule.verify(
            trustBase,
            predicateVerifier,
            transaction
    );
    if (result.getStatus() != VerificationStatus.OK) {
      throw new VerificationException("Invalid token transfer transaction", result);
    }

    ArrayList<CertifiedTransferTransaction> transactions = new ArrayList<>(this.transactions);
    transactions.add(transaction);
    return new Token(this.genesis, transactions);
  }

  /**
   * Verifies genesis and transfer transaction chain integrity.
   *
   * @param trustBase trust base used for certification checks
   * @param predicateVerifier predicate verifier service
   * @param mintJustificationVerifier mint justification verifier service
   * @return verification result with nested per-step verification details
   */
  public VerificationResult<VerificationStatus> verify(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          MintJustificationVerifierService mintJustificationVerifier
  ) {
    List<VerificationResult<?>> results = new ArrayList<>();
    VerificationResult<?> result = CertifiedMintTransactionVerificationRule.verify(
            trustBase,
            predicateVerifier,
            mintJustificationVerifier,
            this.genesis
    );
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("TokenVerification", VerificationStatus.FAIL,
              "Genesis verification failed", results);
    }

    List<VerificationResult<?>> transferResults = new ArrayList<>();
    for (int i = 0; i < this.transactions.size(); i++) {
      CertifiedTransferTransaction transaction = this.transactions.get(i);
      result = CertifiedTransferTransactionVerificationRule.verify(trustBase, predicateVerifier, transaction);
      transferResults.add(result);
      if (result.getStatus() != VerificationStatus.OK) {
        results.add(
                new VerificationResult<>("TokenTransferVerification", VerificationStatus.FAIL, "",
                        transferResults)
        );

        return new VerificationResult<>("TokenVerification", VerificationStatus.FAIL,
                String.format("Transaction[%s] verification failed", i), results);
      }
    }
    results.add(new VerificationResult<>("TokenTransferVerification", VerificationStatus.OK, "",
            transferResults));

    return new VerificationResult<>("TokenVerification", VerificationStatus.OK, "", results);
  }

  /**
   * Serializes this token to CBOR bytes.
   *
   * @return CBOR-encoded token bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
            Token.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(Token.VERSION),
                    this.genesis.toCbor(),
                    CborSerializer.encodeArray(
                            this.transactions.stream().map(Transaction::toCbor).toArray(byte[][]::new))
            )
    );
  }

  @Override
  public String toString() {
    return String.format("Token{genesis=%s, transactions=%s}", this.genesis, this.transactions);
  }
}
