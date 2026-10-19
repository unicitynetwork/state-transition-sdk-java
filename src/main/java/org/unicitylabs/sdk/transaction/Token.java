package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.verification.CertifiedMintTransactionVerificationRule;
import org.unicitylabs.sdk.transaction.verification.CertifiedTransferTransactionVerificationRule;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.util.verification.VerificationException;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable token aggregate containing the certified genesis mint transaction and transfer history.
 */
public final class Token {
  public static final long CBOR_TAG = 39040;
  /**
   * The only accepted wire version. Bumped with the certified-transaction element counts and the
   * transaction encodings below them: without it a token written by an older SDK passes the
   * version check here and then dies deeper down on a CBOR array-length error that never mentions
   * versioning.
   */
  private static final int VERSION = 2;

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

    // Each transfer spends the state the previous one produced. Deriving that here, rather than
    // handing the decoder a half-built token to read it back off, is what makes the chain
    // explicit — and lets a Token be constructed once, from a finished list.
    List<CertifiedTransferTransaction> transactions = new ArrayList<>();
    Transaction previous = genesis;
    for (byte[] transaction : transactionsCbor) {
      CertifiedTransferTransaction decoded = CertifiedTransferTransaction.fromCbor(
              transaction, previous.calculateStateHash(), previous.getRecipient());
      transactions.add(decoded);
      previous = decoded;
    }

    return new Token(genesis, transactions);
  }

  /**
   * Creates a token from a certified genesis transaction and verifies it.
   *
   * @param genesis certified mint transaction
   * @param context shared verification context (trust base + registries)
   * @return verified token instance
   * @throws VerificationException if genesis verification fails
   */
  public static Token mint(
          CertifiedMintTransaction genesis,
          VerificationContext context
  ) {
    Objects.requireNonNull(genesis, "genesis cannot be null");
    Objects.requireNonNull(context, "context cannot be null");

    Token token = new Token(genesis);
    VerificationResult<VerificationStatus> result = token.verify(context);
    if (result.getStatus() != VerificationStatus.OK) {
      throw new VerificationException("Invalid token genesis", result);
    }

    return token;
  }

  /**
   * Returns a new token instance with an additional verified transfer transaction.
   *
   * @param transaction certified transfer transaction to append
   * @param context shared verification context (trust base + registries)
   * @return new token instance with appended transfer
   * @throws VerificationException if transfer verification fails
   */
  public Token transfer(
          CertifiedTransferTransaction transaction,
          VerificationContext context
  ) {
    Objects.requireNonNull(transaction, "transaction cannot be null");
    Objects.requireNonNull(context, "context cannot be null");

    VerificationResult<VerificationStatus> result = CertifiedTransferTransactionVerificationRule.verify(
            transaction,
            context
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
   * <p>Tokens embedded in mint justifications (for example, the burn token of a split) are
   * verified iteratively with a worklist instead of recursion, so arbitrarily long provenance
   * chains do not grow the call stack.
   *
   * @param context shared verification context (trust base + registries)
   * @return verification result with a child result per verified token
   */
  public VerificationResult<VerificationStatus> verify(VerificationContext verificationContext) {
    Objects.requireNonNull(verificationContext, "context cannot be null");
    // one operation spans the token and every nested token
    VerificationContext context = verificationContext.forOperation();

    ArrayDeque<Token> pending = new ArrayDeque<>();
    pending.add(this);

    List<VerificationResult<?>> results = new ArrayList<>();
    for (int tokenIndex = 0; !pending.isEmpty(); tokenIndex++) {
      Token token = pending.poll();
      String rule = String.format("Token[%s:%s]", tokenIndex, token.getId());

      List<VerificationResult<?>> tokenResults = new ArrayList<>();
      VerificationResult<?> result = CertifiedMintTransactionVerificationRule.verify(
              token.genesis,
              context,
              pending::add
      );
      tokenResults.add(result);
      if (result.getStatus() != VerificationStatus.OK) {
        results.add(new VerificationResult<>(rule, VerificationStatus.FAIL,
                "Genesis verification failed", tokenResults));
        return new VerificationResult<>("TokenVerification", VerificationStatus.FAIL,
                String.format("%s verification failed", rule), results);
      }

      List<VerificationResult<?>> transferResults = new ArrayList<>();
      for (int i = 0; i < token.transactions.size(); i++) {
        CertifiedTransferTransaction transaction = token.transactions.get(i);
        result = CertifiedTransferTransactionVerificationRule.verify(transaction, context);
        transferResults.add(result);
        if (result.getStatus() != VerificationStatus.OK) {
          tokenResults.add(
                  new VerificationResult<>("TokenTransferVerification", VerificationStatus.FAIL, "",
                          transferResults)
          );
          results.add(new VerificationResult<>(rule, VerificationStatus.FAIL,
                  String.format("Transaction[%s] verification failed", i), tokenResults));

          return new VerificationResult<>("TokenVerification", VerificationStatus.FAIL,
                  String.format("%s verification failed", rule), results);
        }
      }
      tokenResults.add(new VerificationResult<>("TokenTransferVerification", VerificationStatus.OK, "",
              transferResults));
      results.add(new VerificationResult<>(rule, VerificationStatus.OK, "", tokenResults));
    }

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
