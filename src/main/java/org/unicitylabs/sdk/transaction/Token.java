package org.unicitylabs.sdk.transaction;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.verification.CertifiedMintTransactionVerificationRule;
import org.unicitylabs.sdk.transaction.verification.CertifiedTransferTransactionVerificationRule;
import org.unicitylabs.sdk.util.verification.VerificationException;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Token {
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

  public TokenId getId() {
    return this.genesis.getTokenId();
  }

  public TokenType getType() {
    return this.genesis.getTokenType();
  }

  public Transaction getLatestTransaction() {
    if (this.transactions.isEmpty()) {
      return this.genesis;
    }

    return this.transactions.get(this.transactions.size() - 1);
  }

  public List<CertifiedTransferTransaction> getTransactions() {
    return this.transactions;
  }

  public static Token fromCbor(byte[] bytes) {
    CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != Token.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData());

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != Token.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }
    List<byte[]> transactions = CborDeserializer.decodeArray(data.get(2));

    return new Token(
        CertifiedMintTransaction.fromCbor(data.get(1)),
        transactions.stream().map(CertifiedTransferTransaction::fromCbor)
            .collect(Collectors.toList())
    );
  }

  public static Token mint(RootTrustBase trustBase, PredicateVerifierService predicateVerifier,
      CertifiedMintTransaction genesis) {
    Token token = new Token(genesis);
    VerificationResult<VerificationStatus> result = token.verify(trustBase, predicateVerifier);
    if (result.getStatus() != VerificationStatus.OK) {
      throw new VerificationException("Invalid token genesis", result);
    }

    return token;
  }

  public Token transfer(RootTrustBase trustBase, PredicateVerifierService predicateVerifier,
      CertifiedTransferTransaction transaction) {
    VerificationResult<VerificationStatus> result = CertifiedTransferTransactionVerificationRule.verify(
        trustBase,
        predicateVerifier,
        this.getLatestTransaction(),
        transaction
    );
    if (result.getStatus() != VerificationStatus.OK) {
      throw new VerificationException("Invalid token transfer transaction", result);
    }

    ArrayList<CertifiedTransferTransaction> transactions = new ArrayList<>(this.transactions);
    transactions.add(transaction);
    return new Token(this.genesis, transactions);
  }

  public VerificationResult<VerificationStatus> verify(RootTrustBase trustBase,
      PredicateVerifierService predicateVerifier) {
    List<VerificationResult<?>> results = new ArrayList<>();
    VerificationResult<?> result = CertifiedMintTransactionVerificationRule.verify(trustBase,
        predicateVerifier, this.genesis);
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("TokenVerification", VerificationStatus.FAIL,
          "Genesis verification failed", results);
    }

    List<VerificationResult<?>> transferResults = new ArrayList<>();
    for (int i = 0; i < this.transactions.size(); i++) {
      CertifiedTransferTransaction transaction = this.transactions.get(i);
      result = CertifiedTransferTransactionVerificationRule.verify(trustBase, predicateVerifier,
          i == 0 ? this.genesis : this.transactions.get(i - 1), transaction);
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

  public String toString() {
    return String.format("Token{genesis=%s, transactions=%s}", this.genesis, this.transactions);
  }
}
