package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Steps for {@code shard-id.feature}. Direct tests against {@link ShardId}'s
 * codec, getBit, and isPrefixOf APIs.
 */
public class ShardIdSteps {

  private byte[] encoded;
  private ShardId decoded;
  private byte[] data;
  private Throwable thrown;

  @Given("a ShardId encoded as {string}")
  public void aShardIdEncodedAs(String hex) {
    encoded = parseHex(hex);
  }

  @Given("data starting with {string}")
  public void dataStartingWith(String hex) {
    data = parseHex(hex);
  }

  @When("the ShardId is decoded")
  public void theShardIdIsDecoded() {
    thrown = null;
    decoded = null;
    try {
      decoded = ShardId.decode(encoded);
    } catch (Throwable t) {
      thrown = t;
    }
  }

  @When("isPrefixOf is checked")
  public void isPrefixOfIsChecked() {
    if (decoded == null) {
      decoded = ShardId.decode(encoded);
    }
  }

  @Then("the ShardId length is {int}")
  public void theShardIdLengthIs(int length) {
    assertNotNull(decoded);
    assertEquals(length, decoded.getLength());
  }

  @Then("re-encoding the ShardId produces {string}")
  public void reEncodingProduces(String hex) {
    assertArrayEquals(parseHex(hex), decoded.encode());
  }

  @Then("isPrefixOf returns true")
  public void isPrefixOfReturnsTrue() {
    assertNotNull(decoded);
    assertTrue(decoded.isPrefixOf(data),
        "expected isPrefixOf=true for shard hex / data hex");
  }

  @Then("isPrefixOf returns false")
  public void isPrefixOfReturnsFalse() {
    assertNotNull(decoded);
    assertFalse(decoded.isPrefixOf(data),
        "expected isPrefixOf=false for shard hex / data hex");
  }

  @Then("getBit at index {int} returns {int}")
  public void getBitAtIndexReturns(int index, int expectedBit) {
    assertNotNull(decoded);
    assertEquals(expectedBit, decoded.getBit(index));
  }

  @Then("getBit at index {int} throws {string}")
  public void getBitAtIndexThrows(int index, String marker) {
    assertNotNull(decoded);
    Throwable caught = null;
    try {
      decoded.getBit(index);
    } catch (Throwable t) {
      caught = t;
    }
    assertNotNull(caught, "expected getBit(" + index + ") to throw");
    String msg = caught.getMessage() == null ? "" : caught.getMessage().toLowerCase();
    assertTrue(msg.contains(marker.toLowerCase())
            || (caught instanceof IndexOutOfBoundsException),
        "expected message to contain '" + marker + "' but was: " + caught.getMessage());
  }

  @Then("decoding throws with message containing {string}")
  public void decodingThrowsWithMessage(String marker) {
    assertNotNull(thrown, "expected decode to throw");
    String msg = thrown.getMessage() == null ? "" : thrown.getMessage().toLowerCase();
    assertTrue(msg.contains(marker.toLowerCase()),
        "expected message to contain '" + marker + "' but was: " + thrown.getMessage());
  }

  // ── ShardIdMatchesStateIdRule scenarios ─────────────────────────────────

  private org.unicitylabs.sdk.api.StateId stateIdForRule;
  private org.unicitylabs.sdk.util.verification.VerificationResult<
      org.unicitylabs.sdk.util.verification.VerificationStatus> ruleResult;

  @Given("a ShardId encoded as {string} describing {int} bits")
  public void aShardIdEncodedAsDescribingBits(String hex, int ignoredBits) {
    encoded = parseHex(hex);
  }

  @Given("a StateID with first byte {string}")
  public void aStateIdWithFirstByte(String hex) {
    byte[] data = new byte[32];
    data[0] = parseHex(hex)[0];
    stateIdForRule = org.unicitylabs.sdk.api.StateId.fromCbor(
        org.unicitylabs.sdk.serializer.cbor.CborSerializer.encodeByteString(data));
  }

  @Given("a StateID with first two bytes {string}")
  public void aStateIdWithFirstTwoBytes(String hex) {
    byte[] data = new byte[32];
    byte[] prefix = parseHex(hex);
    data[0] = prefix[0];
    if (prefix.length > 1) {
      data[1] = prefix[1];
    }
    stateIdForRule = org.unicitylabs.sdk.api.StateId.fromCbor(
        org.unicitylabs.sdk.serializer.cbor.CborSerializer.encodeByteString(data));
  }

  @When("ShardIdMatchesStateIdRule.verify runs")
  public void shardIdMatchesStateIdRuleVerifyRuns() {
    assertNotNull(encoded, "no shard hex captured before verify step");
    org.unicitylabs.sdk.api.bft.ShardTreeCertificate stc =
        org.unicitylabs.sdk.api.bft.ShardTreeCertificate.fromCbor(
            org.unicitylabs.sdk.serializer.cbor.CborSerializer.encodeTag(
                org.unicitylabs.sdk.api.bft.ShardTreeCertificate.CBOR_TAG,
                org.unicitylabs.sdk.serializer.cbor.CborSerializer.encodeArray(
                    org.unicitylabs.sdk.serializer.cbor.CborSerializer.encodeUnsignedInteger(1),
                    org.unicitylabs.sdk.serializer.cbor.CborSerializer.encodeByteString(encoded),
                    org.unicitylabs.sdk.serializer.cbor.CborSerializer.encodeArray())));
    ruleResult =
        org.unicitylabs.sdk.transaction.verification.ShardIdMatchesStateIdRule.verify(
            stateIdForRule, stc);
  }

  @Then("the rule status is {string}")
  public void theRuleStatusIs(String expected) {
    assertNotNull(ruleResult, "no rule result captured");
    assertEquals(expected, ruleResult.getStatus().name(),
        "rule message: " + ruleResult.getMessage());
  }

  /** Parses Gherkin "0xHEX" strings, including the empty form "0x". */
  static byte[] parseHex(String s) {
    if (s == null || s.isEmpty()) {
      return new byte[0];
    }
    String cleaned = s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
    if (cleaned.isEmpty()) {
      return new byte[0];
    }
    return HexConverter.decode(cleaned);
  }
}
