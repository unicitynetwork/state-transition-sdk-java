package org.unicitylabs.sdk.functional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.CertifiedMintTransaction;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifier;
import org.unicitylabs.sdk.transaction.verification.TokenIssuanceVerifierService;
import org.unicitylabs.sdk.transaction.verification.VerificationContext;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * F-02: token issuance policy dispatch. Mirrors the JS {@code TokenIssuanceVerifierServiceTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TokenIssuanceVerifierServiceTest {

  private TrustBaseHistoryView trustBase;
  private PredicateVerifierService predicateVerifier;
  private TokenType tokenType;
  private Token token;
  private CertifiedMintTransaction genesis;

  @BeforeAll
  public void setupFixture() throws Exception {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    this.trustBase = aggregatorClient.getTrustBase();
    StateTransitionClient client = new StateTransitionClient(aggregatorClient);
    this.predicateVerifier = PredicateVerifierService.create();

    this.tokenType = TokenType.generate();
    VerificationContext context = new VerificationContext(
            this.trustBase,
            this.predicateVerifier,
            new MintJustificationVerifierService(),
            new TokenIssuanceVerifierService(false));
    this.token = TokenUtils.mintToken(
            client,
            context,
            SignaturePredicate.fromSigningService(SigningService.generate()),
            null,
            NetworkId.LOCAL,
            this.tokenType
    );
    this.genesis = this.token.getGenesis();
  }

  private TokenIssuanceVerifier verifier(VerificationStatus status) {
    return new TokenIssuanceVerifier() {
      @Override
      public TokenType getTokenType() {
        return TokenIssuanceVerifierServiceTest.this.tokenType;
      }

      @Override
      public VerificationResult<VerificationStatus> verify(CertifiedMintTransaction transaction) {
        return new VerificationResult<>("Test", status);
      }
    };
  }

  @Test
  public void rejectsUnregisteredTokenTypeByDefault() {
    Assertions.assertEquals(
            VerificationStatus.FAIL,
            new TokenIssuanceVerifierService().verify(this.genesis).getStatus());
  }

  @Test
  public void acceptsUnregisteredTokenTypeWhenFailOpen() {
    Assertions.assertEquals(
            VerificationStatus.OK,
            new TokenIssuanceVerifierService(false).verify(this.genesis).getStatus());
  }

  @Test
  public void runsTheRegisteredVerifierForATokenType() {
    Assertions.assertEquals(
            VerificationStatus.OK,
            new TokenIssuanceVerifierService(true)
                    .register(verifier(VerificationStatus.OK))
                    .verify(this.genesis)
                    .getStatus());

    Assertions.assertEquals(
            VerificationStatus.FAIL,
            new TokenIssuanceVerifierService()
                    .register(verifier(VerificationStatus.FAIL))
                    .verify(this.genesis)
                    .getStatus());
  }

  @Test
  public void tokenVerifyRejectsAMintWhoseIssuancePolicyFails() {
    // A cryptographically valid token whose issuance policy rejects its type must fail Token.verify.
    VerificationContext context = new VerificationContext(
            this.trustBase,
            this.predicateVerifier,
            new MintJustificationVerifierService(),
            new TokenIssuanceVerifierService().register(verifier(VerificationStatus.FAIL))
    );

    Assertions.assertEquals(
            VerificationStatus.FAIL, this.token.verify(context).getStatus());

    // The same token passes when the issuance policy accepts it.
    VerificationContext accepting = new VerificationContext(
            this.trustBase,
            this.predicateVerifier,
            new MintJustificationVerifierService(),
            new TokenIssuanceVerifierService().register(verifier(VerificationStatus.OK))
    );
    Assertions.assertEquals(
            VerificationStatus.OK, this.token.verify(accepting).getStatus());
  }

  @Test
  public void rejectsDuplicateRegistrationForTheSameTokenType() {
    TokenIssuanceVerifierService service = new TokenIssuanceVerifierService()
            .register(verifier(VerificationStatus.OK));
    IllegalArgumentException exception = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> service.register(verifier(VerificationStatus.OK)));
    Assertions.assertTrue(exception.getMessage().contains("Duplicate token issuance verifier"),
            exception.getMessage());
  }
}
