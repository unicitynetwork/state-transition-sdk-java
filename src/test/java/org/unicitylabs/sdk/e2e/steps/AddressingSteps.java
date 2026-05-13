package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.e2e.support.NametagRegistry;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicateUnlockScript;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.unicitylabs.sdk.unicityid.UnicityIdToken;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Steps for the nametag / addressing features ({@code token-nametag.feature},
 * {@code token-mixed-addressing.feature}, etc.). Mirrors TS
 * {@code addressing.steps.ts}.
 *
 * <p>Addressing methods:
 * <ul>
 *   <li>{@code pubkey} — recipient's signing predicate (default)</li>
 *   <li>{@code nametag} — recipient's previously-registered
 *       {@link UnicityIdToken#getGenesis()} target predicate</li>
 * </ul>
 */
public class AddressingSteps {

  private final TestContext context;

  public AddressingSteps(TestContext context) {
    this.context = context;
  }

  // ── Given: nametag registration (user setup is handled by UserSteps) ──────

  @Given("{word} has registered the nametag {string}")
  public void userHasRegisteredNametag(String userName, String tagWithAt) throws Exception {
    String tag = stripAt(tagWithAt);
    registerNametagFor(userName, tag, "bdd/test");
  }

  @Given("{word} has registered the nametag {string} in domain {string}")
  public void userHasRegisteredNametagInDomain(String userName, String tagWithAt, String domain)
      throws Exception {
    String tag = stripAt(tagWithAt);
    registerNametagFor(userName, tag, domain);
  }

  // ── When: addressing-aware mint ───────────────────────────────────────────

  @When("{word} mints a new token addressed to {word} via {word}")
  public void senderMintsTokenAddressedToRecipientVia(
      String senderName, String recipientName, String method) throws Exception {
    ensureUser(senderName);
    ensureUser(recipientName);
    Predicate recipientPredicate = resolveRecipientPredicate(recipientName, method);
    context.setAddressingMethod(method);

    Token token = TokenUtils.mintToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        recipientPredicate);
    context.setCurrentToken(token);
    context.setCurrentUser(senderName);
    context.addUserToken(recipientName, token);
  }

  // ── When: addressing-aware transfer ───────────────────────────────────────

  @When("{word} transfers the token to {word} via {word}")
  public void senderTransfersTokenToRecipientVia(
      String senderName, String recipientName, String method) throws Exception {
    ensureUser(senderName);
    ensureUser(recipientName);
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token to transfer");
    SigningService senderSigning = context.getUserSigningServices().get(senderName);
    Predicate recipientPredicate = resolveRecipientPredicate(recipientName, method);
    context.setAddressingMethod(method);

    Token transferred = TokenUtils.transferToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        sourceToken.toCbor(),
        recipientPredicate,
        senderSigning);
    context.setCurrentToken(transferred);
    context.setCurrentUser(senderName);
    context.addUserToken(recipientName, transferred);
  }

  // ── Then: assertions ──────────────────────────────────────────────────────

  @Then("the current token verifies")
  public void theCurrentTokenVerifies() {
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token to verify");
    assertEquals(
        VerificationStatus.OK,
        token.verify(
            context.getTrustBase(),
            context.getPredicateVerifier(),
            context.getMintJustificationVerifier()).getStatus());
  }

  @Then("the current token can be spent by {word}")
  public void currentTokenCanBeSpentBy(String ownerName) throws Exception {
    Token sourceToken = context.getCurrentToken();
    assertNotNull(sourceToken, "no current token");
    SigningService ownerSigning = context.getUserSigningServices().get(ownerName);
    assertNotNull(ownerSigning, "no signing key for " + ownerName);

    // Build a bystander recipient — we just want to prove the owner can spend.
    SigningService bystanderSigning = SigningService.generate();
    SignaturePredicate bystanderPredicate =
        SignaturePredicate.fromSigningService(bystanderSigning);

    byte[] x = new byte[32];
    new java.security.SecureRandom().nextBytes(x);
    TransferTransaction tx = TransferTransaction.create(
        sourceToken, bystanderPredicate, x, CborSerializer.encodeArray());

    CertificationData cert = CertificationData.fromTransaction(
        tx, SignaturePredicateUnlockScript.create(tx, ownerSigning));
    CertificationResponse response = context.getClient().submitCertificationRequest(cert).get();
    assertEquals(CertificationStatus.SUCCESS, response.getStatus());
  }

  @When("a {int}-hop transfer chain runs using addressing sequence {string}")
  public void mixedChainRuns(int hopCount, String csv) throws Exception {
    String[] methods = csv.split(",");
    for (int i = 0; i < methods.length; i++) {
      methods[i] = methods[i].trim();
    }
    if (methods.length != hopCount) {
      throw new IllegalArgumentException(
          "sequence length " + methods.length + " != declared hops " + hopCount);
    }

    // Create N+1 anonymous users (User0..UserN). Register nametag for any
    // recipient whose hop uses 'nametag'.
    String[] users = new String[hopCount + 1];
    for (int i = 0; i <= hopCount; i++) {
      users[i] = "MixedUser" + i + "_" + System.nanoTime() + "_" + i;
      ensureUser(users[i]);
    }
    for (int i = 0; i < hopCount; i++) {
      if ("nametag".equals(methods[i])) {
        registerNametagFor(users[i + 1], "mixed-" + i, "bdd/test");
      }
    }

    // Mint a token to user 0 first.
    SignaturePredicate firstPredicate =
        (SignaturePredicate) context.getUserPredicates().get(users[0]);
    Token current = TokenUtils.mintToken(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        context.getMintJustificationVerifier(),
        firstPredicate);

    // Walk the chain.
    for (int i = 0; i < hopCount; i++) {
      Predicate recipientPredicate = resolveRecipientPredicate(users[i + 1], methods[i]);
      SigningService senderSigning = context.getUserSigningServices().get(users[i]);
      current = TokenUtils.transferToken(
          context.getClient(),
          context.getTrustBase(),
          context.getPredicateVerifier(),
          context.getMintJustificationVerifier(),
          current.toCbor(),
          recipientPredicate,
          senderSigning);
    }
    context.setCurrentToken(current);
  }

  @Then("the current token's CBOR does not contain the bytes of {string}")
  public void currentTokenCborDoesNotContainBytesOf(String tagWithAt) {
    Token token = context.getCurrentToken();
    assertNotNull(token, "no current token");
    byte[] cbor = token.toCbor();
    byte[] needle = tagWithAt.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    int idx = indexOf(cbor, needle);
    assertTrue(idx < 0,
        "token CBOR contains '" + tagWithAt + "' at byte offset " + idx);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private void ensureUser(String userName) {
    if (!context.getUserSigningServices().containsKey(userName)) {
      SigningService signing = SigningService.generate();
      SignaturePredicate predicate = SignaturePredicate.fromSigningService(signing);
      context.getUserSigningServices().put(userName, signing);
      context.getUserPredicates().put(userName, predicate);
      context.getUserAddresses().put(userName, predicate);
    }
  }

  private void registerNametagFor(String userName, String tag, String domain) throws Exception {
    ensureUser(userName);
    SignaturePredicate userPredicate =
        (SignaturePredicate) context.getUserPredicates().get(userName);
    UnicityIdToken nametag = NametagRegistry.registerNametag(
        context.getClient(),
        context.getTrustBase(),
        context.getPredicateVerifier(),
        userPredicate,
        tag,
        domain);
    context.getUserNametags().put(userName, nametag);
  }

  private Predicate resolveRecipientPredicate(String recipientName, String method) {
    if ("pubkey".equals(method)) {
      return context.getUserPredicates().get(recipientName);
    }
    if ("nametag".equals(method)) {
      UnicityIdToken nametag = context.getUserNametags().get(recipientName);
      assertNotNull(nametag, "no nametag registered for " + recipientName);
      return nametag.getGenesis().getTargetPredicate();
    }
    throw new IllegalArgumentException("Unsupported addressing method: " + method);
  }

  private static String stripAt(String tag) {
    return tag.startsWith("@") ? tag.substring(1) : tag;
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    if (needle.length == 0) {
      return 0;
    }
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
