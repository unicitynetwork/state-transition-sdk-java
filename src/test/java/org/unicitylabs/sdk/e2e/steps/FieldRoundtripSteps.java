package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.security.SecureRandom;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.unicityid.UnicityId;
import org.unicitylabs.sdk.unicityid.UnicityIdMintTransaction;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * CBOR round-trip steps for {@code mint-transaction-fields.feature},
 * {@code transfer-transaction-fields.feature}, and
 * {@code unicity-id-mint-transaction-envelope.feature}. These exercise pure
 * encode/decode invariants — no aggregator round-trip required.
 */
public class FieldRoundtripSteps {

  private final TestContext context;

  // MintTransaction fields
  private byte[] mintJustification;
  private byte[] mintData;
  private MintTransaction mintTx;
  private MintTransaction decodedMintTx;

  // TransferTransaction fields
  private TransferTransaction transferTx;
  private TransferTransaction decodedTransferTx;
  private byte[] originalStateMask;

  // UnicityIdMintTransaction fields
  private UnicityIdMintTransaction unicityIdTx;
  private UnicityIdMintTransaction decodedUnicityIdTx;
  private SignaturePredicate originalLockScript;
  private SignaturePredicate originalRecipient;
  private SignaturePredicate originalTargetPredicate;
  private UnicityId originalUnicityId;
  private TokenType originalTokenType;
  private TokenId originalTokenId;

  public FieldRoundtripSteps(TestContext context) {
    this.context = context;
  }

  // ── MintTransaction round-trip ───────────────────────────────────────────

  @Given("a MintTransaction is built with justification {string} and data {string}")
  public void mintTxIsBuilt(String justificationHex, String dataHex) {
    mintJustification = "null".equals(justificationHex) ? null : HexConverter.decode(justificationHex);
    mintData = "null".equals(dataHex) ? null : HexConverter.decode(dataHex);

    SignaturePredicate recipient =
        SignaturePredicate.fromSigningService(SigningService.generate());
    mintTx = MintTransaction.create(
        recipient,
        TokenId.generate(),
        TokenType.generate(),
        mintJustification,
        mintData);
  }

  @When("the MintTransaction is encoded and decoded")
  public void mintTxEncodedAndDecoded() {
    decodedMintTx = MintTransaction.fromCbor(mintTx.toCbor());
  }

  @Then("the decoded justification matches {string}")
  public void decodedJustificationMatches(String expected) {
    byte[] expectedBytes = "null".equals(expected) ? null : HexConverter.decode(expected);
    byte[] actual = decodedMintTx.getJustification().orElse(null);
    if (expectedBytes == null) {
      assertEquals(null, actual, "expected justification to be null");
    } else {
      assertNotNull(actual, "expected non-null justification");
      assertArrayEquals(expectedBytes, actual);
    }
  }

  @Then("the decoded data matches {string}")
  public void decodedDataMatches(String expected) {
    byte[] expectedBytes = "null".equals(expected) ? null : HexConverter.decode(expected);
    byte[] actual = decodedMintTx.getData().orElse(null);
    if (expectedBytes == null) {
      assertEquals(null, actual, "expected data to be null");
    } else {
      assertNotNull(actual, "expected non-null data");
      assertArrayEquals(expectedBytes, actual);
    }
  }

  // ── TransferTransaction round-trip (stateMask) ───────────────────────────

  @Given("a TransferTransaction is built from {word}'s token with a stateMask of {int} bytes")
  public void transferTxBuiltWithStateMask(String userName, int length) {
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token (call 'X has a minted token' first)");
    SignaturePredicate recipient = SignaturePredicate.fromSigningService(SigningService.generate());

    originalStateMask = new byte[length];
    if (length > 0) {
      new SecureRandom().nextBytes(originalStateMask);
    }
    transferTx = TransferTransaction.create(
        sourceToken, recipient, originalStateMask, new byte[0]);
  }

  @When("the TransferTransaction is encoded and decoded")
  public void transferTxEncodedAndDecoded() {
    decodedTransferTx = TransferTransaction.fromCbor(
        transferTx.toCbor(), context.getCurrentToken());
  }

  @Then("the decoded stateMask is {int} bytes")
  public void decodedStateMaskIsBytes(int length) {
    byte[] mask = decodedTransferTx.getStateMask();
    assertEquals(length, mask.length);
  }

  @Then("the decoded stateMask byte-for-byte equals the original")
  public void decodedStateMaskByteForByteEqualsOriginal() {
    assertArrayEquals(originalStateMask, decodedTransferTx.getStateMask());
  }

  // ── UnicityIdMintTransaction round-trip ──────────────────────────────────

  @Given("a UnicityIdMintTransaction is built with a sample lockScript, recipient, unicityId, "
      + "tokenType, and targetPredicate")
  public void unicityIdMintTxIsBuilt() {
    originalLockScript = SignaturePredicate.fromSigningService(SigningService.generate());
    originalRecipient = SignaturePredicate.fromSigningService(SigningService.generate());
    originalTargetPredicate = SignaturePredicate.fromSigningService(SigningService.generate());
    originalUnicityId = new UnicityId("testuser", "unicity-labs/test");
    originalTokenType = TokenType.generate();

    unicityIdTx = UnicityIdMintTransaction.create(
        originalLockScript,
        originalRecipient,
        originalUnicityId,
        originalTokenType,
        originalTargetPredicate);
    originalTokenId = unicityIdTx.getTokenId();
  }

  @When("the UnicityIdMintTransaction is encoded and decoded")
  public void unicityIdMintTxEncodedAndDecoded() {
    decodedUnicityIdTx = UnicityIdMintTransaction.fromCbor(unicityIdTx.toCbor());
  }

  @Then("the decoded transaction's tokenId equals the original")
  public void decodedTokenIdEqualsOriginal() {
    assertEquals(originalTokenId, decodedUnicityIdTx.getTokenId());
  }

  @Then("the decoded transaction's tokenType equals the original")
  public void decodedTokenTypeEqualsOriginal() {
    assertEquals(originalTokenType, decodedUnicityIdTx.getTokenType());
  }

  @Then("the decoded transaction's lockScript encodes to the original lockScript bytes")
  public void decodedLockScriptEncodesToOriginal() {
    assertArrayEquals(
        org.unicitylabs.sdk.predicate.EncodedPredicate.fromPredicate(originalLockScript).toCbor(),
        decodedUnicityIdTx.getLockScript().toCbor());
  }

  @Then("the decoded transaction's recipient encodes to the original recipient bytes")
  public void decodedRecipientEncodesToOriginal() {
    assertArrayEquals(
        org.unicitylabs.sdk.predicate.EncodedPredicate.fromPredicate(originalRecipient).toCbor(),
        decodedUnicityIdTx.getRecipient().toCbor());
  }

  @Then("the decoded transaction's targetPredicate encodes to the original targetPredicate bytes")
  public void decodedTargetPredicateEncodesToOriginal() {
    assertArrayEquals(
        org.unicitylabs.sdk.predicate.EncodedPredicate.fromPredicate(originalTargetPredicate)
            .toCbor(),
        org.unicitylabs.sdk.predicate.EncodedPredicate
            .fromPredicate(decodedUnicityIdTx.getTargetPredicate())
            .toCbor());
  }

  @Then("the decoded transaction's unicityId encodes to the original unicityId bytes")
  public void decodedUnicityIdEncodesToOriginal() {
    assertArrayEquals(originalUnicityId.toCbor(), decodedUnicityIdTx.getUnicityId().toCbor());
  }
}
