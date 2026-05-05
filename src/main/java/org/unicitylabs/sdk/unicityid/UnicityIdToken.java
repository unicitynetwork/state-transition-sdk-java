package org.unicitylabs.sdk.unicityid;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.verification.CertifiedUnicityIdMintTransactionVerificationRule;
import org.unicitylabs.sdk.util.verification.VerificationException;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Token whose genesis is a {@link CertifiedUnicityIdMintTransaction}. The token's identifier is
 * deterministically derived from a {@link UnicityId}.
 */
public final class UnicityIdToken {

  private final CertifiedUnicityIdMintTransaction genesis;

  private UnicityIdToken(CertifiedUnicityIdMintTransaction genesis) {
    this.genesis = genesis;
  }

  /**
   * Returns the certified genesis mint transaction.
   *
   * @return genesis transaction
   */
  public CertifiedUnicityIdMintTransaction getGenesis() {
    return this.genesis;
  }

  /**
   * Returns the token id.
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
   * Returns the unicity id used to derive this token's identifier.
   *
   * @return unicity id
   */
  public UnicityId getUnicityId() {
    return this.genesis.getUnicityId();
  }

  /**
   * Deserialize a unicity id token from CBOR bytes.
   *
   * @param bytes CBOR bytes
   *
   * @return decoded token
   */
  public static UnicityIdToken fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes, 1);
    return new UnicityIdToken(CertifiedUnicityIdMintTransaction.fromCbor(data.get(0)));
  }

  /**
   * Build a unicity id token from a certified genesis transaction and verify it.
   *
   * @param trustBase trust base used for certification verification
   * @param predicateVerifier predicate verifier service
   * @param genesis certified mint transaction
   *
   * @return verified token
   *
   * @throws VerificationException if genesis verification fails
   */
  public static UnicityIdToken mint(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          CertifiedUnicityIdMintTransaction genesis
  ) {
    Objects.requireNonNull(trustBase, "trustBase cannot be null");
    Objects.requireNonNull(predicateVerifier, "predicateVerifier cannot be null");
    Objects.requireNonNull(genesis, "genesis cannot be null");

    VerificationResult<VerificationStatus> result =
            CertifiedUnicityIdMintTransactionVerificationRule.verify(
                    trustBase,
                    predicateVerifier,
                    genesis,
                    null
            );
    if (result.getStatus() != VerificationStatus.OK) {
      throw new VerificationException("Invalid token genesis", result);
    }

    return new UnicityIdToken(genesis);
  }

  /**
   * Serialize this token to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(this.genesis.toCbor());
  }

  /**
   * Verify the token by validating its certified mint transaction against an expected issuer.
   *
   * @param trustBase trust base used for certification verification
   * @param predicateVerifier predicate verifier service
   * @param issuerPublicKey expected issuer public key
   *
   * @return verification result
   * @throws NullPointerException if {@code issuerPublicKey} is {@code null}
   */
  public VerificationResult<VerificationStatus> verify(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          byte[] issuerPublicKey
  ) {
    Objects.requireNonNull(trustBase, "trustBase cannot be null");
    Objects.requireNonNull(predicateVerifier, "predicateVerifier cannot be null");
    Objects.requireNonNull(issuerPublicKey, "issuerPublicKey cannot be null");

    List<VerificationResult<?>> results = new ArrayList<>();
    VerificationResult<VerificationStatus> result = CertifiedUnicityIdMintTransactionVerificationRule.verify(
            trustBase,
            predicateVerifier,
            this.genesis,
            issuerPublicKey
    );
    results.add(result);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("TokenVerification", VerificationStatus.FAIL, "", results);
    }

    return new VerificationResult<>("TokenVerification", VerificationStatus.OK, "", results);
  }

  @Override
  public String toString() {
    return String.format("UnicityIdToken{genesis=%s}", this.genesis);
  }
}
