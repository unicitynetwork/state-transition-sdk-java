package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.concurrent.atomic.AtomicInteger;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.functional.payment.TestPaymentData;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.CertifiedMintTransaction;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifier;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.util.InclusionProofUtils;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Steps for {@code mint-justification-registry.feature}. Mirrors the TS step
 * file's pattern: build a real {@link CertifiedMintTransaction} via the
 * aggregator (which doesn't inspect the justification's inner CBOR shape) and
 * use it to drive {@link MintJustificationVerifierService#verify}.
 */
public class MintJustificationRegistrySteps {

  private final TestContext context;

  private MintJustificationVerifierService freshMjv;
  private VerificationResult<VerificationStatus> lastResult;
  private Throwable registrationError;
  private StubMintJustificationVerifier stub;

  public MintJustificationRegistrySteps(TestContext context) {
    this.context = context;
  }

  @Given("a fresh MintJustificationVerifierService is created")
  public void aFreshMjvIsCreated() {
    freshMjv = new MintJustificationVerifierService();
    registrationError = null;
    lastResult = null;
    stub = null;
  }

  @Given("a SplitMintJustificationVerifier is registered")
  public void aSplitMintJustificationVerifierIsRegistered() {
    freshMjv.register(new SplitMintJustificationVerifier(
        context.getTrustBase(), context.getPredicateVerifier(), TestPaymentData::decode));
  }

  @Given("a stub verifier for tag {int} is registered")
  public void aStubVerifierForTagIsRegistered(int tag) {
    stub = new StubMintJustificationVerifier(tag);
    freshMjv.register(stub);
  }

  @When("a second verifier with the same tag is registered")
  public void aSecondVerifierWithSameTagIsRegistered() {
    try {
      freshMjv.register(new SplitMintJustificationVerifier(
          context.getTrustBase(), context.getPredicateVerifier(), TestPaymentData::decode));
    } catch (Throwable t) {
      registrationError = t;
    }
  }

  @Then("the registration error message contains {string}")
  public void registrationErrorMessageContains(String marker) {
    assertNotNull(registrationError, "expected duplicate registration to throw");
    String msg = registrationError.getMessage();
    assertTrue(msg != null && msg.toLowerCase().contains(marker.toLowerCase()),
        "expected message to contain '" + marker + "' but was: " + msg);
  }

  @When("verify is invoked on a CertifiedMintTransaction with null justification")
  public void verifyOnNullJustification() throws Exception {
    CertifiedMintTransaction tx = buildRealCertifiedMint(null);
    lastResult = freshMjv.verify(tx);
  }

  @When("verify is invoked on a CertifiedMintTransaction whose justification uses tag {int}")
  public void verifyOnCustomTagJustification(int tag) throws Exception {
    byte[] justification = CborSerializer.encodeTag(tag, CborSerializer.encodeArray());
    CertifiedMintTransaction tx = buildRealCertifiedMint(justification);
    lastResult = freshMjv.verify(tx);
  }

  @Then("the result status is OK")
  public void resultStatusIsOk() {
    assertNotNull(lastResult, "no verification result captured");
    assertEquals(VerificationStatus.OK, lastResult.getStatus(),
        "expected OK but got: " + lastResult.getStatus()
            + " — " + lastResult.getMessage());
  }

  @Then("the result status is FAIL")
  public void resultStatusIsFail() {
    assertNotNull(lastResult, "no verification result captured");
    assertEquals(VerificationStatus.FAIL, lastResult.getStatus(),
        "expected FAIL but got: " + lastResult.getStatus());
  }

  @Then("the registry result message contains {string}")
  public void registryResultMessageContains(String marker) {
    assertNotNull(lastResult, "no verification result captured");
    String msg = lastResult.getMessage();
    assertTrue(msg != null && msg.toLowerCase().contains(marker.toLowerCase()),
        "expected message to contain '" + marker + "' but was: " + msg);
  }

  @Then("the stub verifier was invoked exactly once")
  public void stubVerifierWasInvokedOnce() {
    assertNotNull(stub, "no stub verifier registered");
    assertEquals(1, stub.getInvocations(),
        "expected stub to be invoked once, got " + stub.getInvocations());
  }

  /**
   * Builds a real {@link CertifiedMintTransaction} by minting through the
   * aggregator and then constructing the cert wrapper directly via
   * {@link MintTransaction#toCertifiedTransaction}. Importantly we bypass
   * {@link org.unicitylabs.sdk.transaction.Token#mint} which would otherwise
   * invoke the registry's verify and reject our synthetic justification.
   *
   * <p>Mirrors TS's duck-typed cast: the registry only inspects
   * {@code getJustification()}, so any real-shaped certified-mint with an
   * arbitrary justification payload works as a test fixture.
   */
  private CertifiedMintTransaction buildRealCertifiedMint(byte[] justification) throws Exception {
    SignaturePredicate recipient =
        SignaturePredicate.fromSigningService(SigningService.generate());
    MintTransaction tx = MintTransaction.create(
        recipient,
        TokenId.generate(),
        TokenType.generate(),
        justification,
        null);

    CertificationData cd = CertificationData.fromMintTransaction(tx);
    CertificationResponse resp = context.getClient().submitCertificationRequest(cd).get();
    if (resp.getStatus() != CertificationStatus.SUCCESS) {
      throw new RuntimeException(
          "Aggregator rejected certification: " + resp.getStatus());
    }

    org.unicitylabs.sdk.api.InclusionProof proof = InclusionProofUtils.waitInclusionProof(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        tx).get();

    return tx.toCertifiedTransaction(
        context.getTrustBase(), context.getPredicateVerifier(), proof);
  }

  // ── Stub verifier ───────────────────────────────────────────────────────

  private static final class StubMintJustificationVerifier implements MintJustificationVerifier {
    private final long tag;
    private final AtomicInteger invocations = new AtomicInteger(0);

    StubMintJustificationVerifier(long tag) {
      this.tag = tag;
    }

    @Override
    public long getTag() {
      return tag;
    }

    @Override
    public VerificationResult<VerificationStatus> verify(
        CertifiedMintTransaction transaction, MintJustificationVerifierService svc) {
      invocations.incrementAndGet();
      return new VerificationResult<>("StubVerifier", VerificationStatus.OK);
    }

    int getInvocations() {
      return invocations.get();
    }
  }
}
