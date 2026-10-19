package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.serializer.json.JsonSerializationException;

class RootTrustBaseUnsignedJsonTest {

  private static String json(String epoch, String start, String threshold, String stake) {
    return "{\"version\":1,\"networkId\":2,\"epoch\":" + epoch + ",\"epochStartRound\":" + start
        + ",\"rootNodes\":[{\"nodeId\":\"root-1\",\"sigKey\":\"0x02aa\",\"stake\":" + stake
        + "}],\"quorumThreshold\":" + threshold + ",\"stateHash\":\"\",\"changeRecordHash\":\"\","
        + "\"previousEntryHash\":\"\",\"signatures\":{}}";
  }

  @Test
  void anEpochBelow2To63ReadsAsPlainNumberOrString() {
    assertEquals(5L, RootTrustBase.fromJson(json("5", "1", "1", "1")).getEpoch());
    assertEquals(5L, RootTrustBase.fromJson(json("\"5\"", "\"1\"", "\"1\"", "\"1\"")).getEpoch());
  }

  @Test
  void anEpochOf2To63ReadsWithoutLosingTheBit() {
    RootTrustBase trustBase = RootTrustBase.fromJson(
        json("9223372036854775808", "9223372036854775809", "1", "1"));
    assertEquals(Long.MIN_VALUE, trustBase.getEpoch());
    assertEquals(BigInteger.ONE.shiftLeft(63), trustBase.getEpochUnsigned());
    assertEquals(BigInteger.ONE.shiftLeft(63).add(BigInteger.ONE),
        trustBase.getEpochStartRoundUnsigned());
  }

  @Test
  void theLargestEpochAndTheValueFromTheSharedVectorRead() {
    RootTrustBase max = RootTrustBase.fromJson(json("18446744073709551615", "1", "1", "1"));
    assertEquals(-1L, max.getEpoch());
    assertEquals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), max.getEpochUnsigned());
    // as a string too
    assertEquals(-1L, RootTrustBase.fromJson(json("\"18446744073709551615\"", "1", "1", "1"))
        .getEpoch());
    // the epoch of the non-inclusion vector authority/big-epoch.json: 2^64 - 5
    assertEquals(-5L, RootTrustBase.fromJson(json("18446744073709551611", "1", "1", "1"))
        .getEpoch());
  }

  @Test
  void theRoundTripIsLosslessForTheWholeRange() {
    for (String epoch : new String[] {"0", "1", "9223372036854775807", "9223372036854775808",
        "18446744073709551611", "18446744073709551615"}) {
      RootTrustBase read = RootTrustBase.fromJson(json(epoch, epoch, "1", "1"));
      String written = read.toJson();
      assertEquals(true, written.contains("\"epoch\":\"" + epoch + "\""), written);
      RootTrustBase again = RootTrustBase.fromJson(written);
      assertEquals(read.getEpoch(), again.getEpoch());
      assertEquals(read.getEpochStartRound(), again.getEpochStartRound());
      assertEquals(epoch, new BigInteger(epoch).toString());
      assertEquals(new BigInteger(epoch), again.getEpochUnsigned());
    }
  }

  @Test
  void aValueOutsideTheUnsigned64BitRangeIsRefused() {
    assertThrows(JsonSerializationException.class,
        () -> RootTrustBase.fromJson(json("18446744073709551616", "1", "1", "1")));
    assertThrows(JsonSerializationException.class,
        () -> RootTrustBase.fromJson(json("-1", "1", "1", "1")));
    assertThrows(JsonSerializationException.class,
        () -> RootTrustBase.fromJson(json("\"-1\"", "1", "1", "1")));
    assertThrows(JsonSerializationException.class,
        () -> RootTrustBase.fromJson(json("1.5", "1", "1", "1")));
    assertThrows(JsonSerializationException.class,
        () -> RootTrustBase.fromJson(json("\"0x10\"", "1", "1", "1")));
    assertThrows(JsonSerializationException.class,
        () -> RootTrustBase.fromJson(json("\" 1\"", "1", "1", "1")));
    assertThrows(JsonSerializationException.class,
        () -> RootTrustBase.fromJson(json("true", "1", "1", "1")));
  }

  @Test
  void aStakeAbove2To63IsPositiveStake() {
    RootTrustBase trustBase = RootTrustBase.fromJson(json("1", "1", "1", "9223372036854775808"));
    RootTrustBase.NodeInfo node = trustBase.getRootNode("root-1");
    assertEquals(Long.MIN_VALUE, node.getStakedAmount());
    assertEquals(BigInteger.ONE.shiftLeft(63), node.getStakedAmountUnsigned());
    assertThrows(JsonSerializationException.class,
        () -> RootTrustBase.fromJson(json("1", "1", "1", "0")));
  }

  @Test
  void anUnsupportedVersionAboveTwoToThe63IsAnErrorNotOne() {
    assertThrows(JsonSerializationException.class, () -> RootTrustBase.fromJson(
        json("1", "1", "1", "1").replace("\"version\":1", "\"version\":9223372036854775809")));
  }

  @Test
  void aThresholdAboveTheNodeCountIsRefusedWhateverItsSign() {
    assertThrows(JsonSerializationException.class,
        () -> RootTrustBase.fromJson(json("1", "1", "9223372036854775808", "1")));
    assertThrows(JsonSerializationException.class,
        () -> RootTrustBase.fromJson(json("1", "1", "0", "1")));
  }
}
