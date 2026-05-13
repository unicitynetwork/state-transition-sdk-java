package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.e2e.support.ShardAwareAggregatorClient;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/**
 * Steps for {@code routing-byte-source.feature}: pin the byte source the
 * shard-router reads from, regression-guarding the pre-#141 byte-31 LSB
 * convention.
 */
public class RoutingByteSourceSteps {

  private StateId stateId;
  private Integer pickedShard;

  @Given("a synthetic StateID with byte 0 {string} and byte 31 {string}")
  public void aSyntheticStateIdWithByte0AndByte31(String byte0Hex, String byte31Hex) {
    byte[] data = new byte[32];
    data[0] = (byte) parseHexByte(byte0Hex);
    data[31] = (byte) parseHexByte(byte31Hex);
    stateId = StateId.fromCbor(CborSerializer.encodeByteString(data));
  }

  @When("ShardAwareAggregatorClient.getShardForStateId runs in {word} mode with shardIdLength {int}")
  public void getShardForStateIdRuns(String mode, int shardIdLength) {
    assertNotNull(stateId, "no stateId set");
    ShardAwareAggregatorClient.RoutingMode routingMode;
    switch (mode) {
      case "lsb":
        routingMode = ShardAwareAggregatorClient.RoutingMode.LSB;
        break;
      case "msb":
        routingMode = ShardAwareAggregatorClient.RoutingMode.MSB;
        break;
      default:
        throw new IllegalArgumentException("unknown routing mode: " + mode);
    }
    pickedShard = ShardAwareAggregatorClient.getShardForStateId(stateId, shardIdLength, routingMode);
  }

  @Then("the picked shard equals {int}")
  public void thePickedShardEquals(int expected) {
    assertNotNull(pickedShard, "no shard computed");
    assertEquals(expected, pickedShard.intValue(),
        "picked=" + pickedShard + " (data[0]=0x" + Integer.toHexString(stateId.getData()[0] & 0xff)
            + ", data[31]=0x" + Integer.toHexString(stateId.getData()[31] & 0xff) + ")");
  }

  @Then("the picked shard is one of {string}")
  public void thePickedShardIsOneOf(String csv) {
    assertNotNull(pickedShard, "no shard computed");
    Set<Integer> allowed = new HashSet<>();
    for (String s : Arrays.asList(csv.split(","))) {
      allowed.add(Integer.parseInt(s.trim()));
    }
    assertTrue(allowed.contains(pickedShard),
        "expected shard in " + allowed + ", got " + pickedShard);
  }

  private static int parseHexByte(String hex) {
    String trimmed = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    return Integer.parseInt(trimmed, 16);
  }
}
