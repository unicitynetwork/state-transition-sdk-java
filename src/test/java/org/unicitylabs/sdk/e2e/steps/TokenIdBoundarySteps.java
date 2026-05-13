package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.cucumber.java.en.When;
import java.security.SecureRandom;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;

public class TokenIdBoundarySteps {

  private final TestContext context;

  public TokenIdBoundarySteps(TestContext context) {
    this.context = context;
  }

  @When("the user submits a mint request with a {int}-byte token ID")
  public void theUserSubmitsMintRequestWithNByteTokenId(int length) throws Exception {
    submitMintWithTokenIdBytes(length > 0 ? randomBytes(length) : new byte[0]);
  }

  @When("the user submits a mint request with a {int}-byte token ID again")
  public void theUserSubmitsMintRequestWithNByteTokenIdAgain(int length) throws Exception {
    // Second submission with a length-byte token ID — for 0-byte this collides
    // with the prior submission because SHA256 of empty is deterministic; for
    // non-zero lengths we'd need to thread the same bytes through context, which
    // isn't needed for any current scenario.
    submitMintWithTokenIdBytes(length > 0 ? randomBytes(length) : new byte[0]);
  }

  private void submitMintWithTokenIdBytes(byte[] idBytes) throws Exception {
    String user = context.getCurrentUser() != null ? context.getCurrentUser() : "Alice";
    context.setCurrentUser(user);
    Predicate recipient = context.getUserAddresses().get(user);
    assertNotNull(recipient, "user " + user + " has no signing key / address");

    MintTransaction transaction = MintTransaction.create(recipient, new TokenId(idBytes), TokenType.generate(), null, CborSerializer.encodeArray());

    CertificationData certificationData = CertificationData.fromMintTransaction(transaction);
    CertificationResponse response =
        context.getClient().submitCertificationRequest(certificationData).get();
    context.setLastCertificationResponse(response);
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }
}
