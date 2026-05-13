package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/**
 * Steps for {@code state-id-encoding.feature}. Verifies that
 * {@code StateId.fromCbor} accepts only 32-byte payloads and rejects legacy
 * v1 (34-byte algorithm-prefixed) StateIDs.
 */
public class StateIdEncodingSteps {

  private byte[] payload;
  private StateId decoded;
  private Throwable thrown;

  @Given("a CBOR byte string of length {int}")
  public void aCborByteStringOfLength(int length) {
    byte[] raw = new byte[length];
    payload = CborSerializer.encodeByteString(raw);
  }

  @Given("a CBOR byte string of length {int} starting with the sha256 algorithm prefix")
  public void aCborByteStringWithSha256Prefix(int length) {
    byte[] raw = new byte[length];
    // Algorithm prefix in the legacy v1 format: 2 bytes encoding the SHA-256
    // identifier. Concrete bytes don't matter for this rejection test.
    raw[0] = (byte) ((HashAlgorithm.SHA256.getValue() >> 8) & 0xff);
    raw[1] = (byte) (HashAlgorithm.SHA256.getValue() & 0xff);
    payload = CborSerializer.encodeByteString(raw);
  }

  @When("StateId.fromCBOR is invoked")
  public void stateIdFromCborIsInvoked() {
    decoded = null;
    thrown = null;
    try {
      decoded = StateId.fromCbor(payload);
    } catch (Throwable t) {
      thrown = t;
    }
  }

  @Then("the StateId decode succeeds")
  public void theStateIdDecodeSucceeds() {
    assertNotNull(decoded, "expected successful decode but got: "
        + (thrown != null ? thrown.getMessage() : "no result"));
  }

  @Then("the StateId decode throws with message containing {string}")
  public void theStateIdDecodeThrowsWithMessage(String marker) {
    assertNotNull(thrown, "expected decode to throw but it succeeded");
    String msg = thrown.getMessage();
    if (msg == null) {
      Throwable cause = thrown.getCause();
      msg = cause != null ? cause.getMessage() : "<no message>";
    }
    assertTrue(msg != null && msg.toLowerCase().contains(marker.toLowerCase()),
        "expected message to contain '" + marker + "' but was: " + msg);
  }

}
