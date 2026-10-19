package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.util.HexConverter;

class InputRecordTest {

  /** The certified empty tree: hash and summary value are CBOR null. */
  private static final String EMPTY_TREE = "d9985a8a010000f6f6f600f600f6";

  @Test
  void theCertifiedEmptyTreeRecordRoundTrips() {
    byte[] bytes = HexConverter.decode(EMPTY_TREE);
    InputRecord record = InputRecord.fromCbor(bytes);

    assertNull(record.getHash());
    assertNull(record.getSummaryValue());
    assertNull(record.getPreviousHash());
    assertNull(record.getBlockHash());
    assertNull(record.getExecutedTransactionsHash());
    assertArrayEquals(bytes, record.toCbor());
    assertEquals(InputRecord.fromCbor(bytes), record);
    assertNull(record.structureFailure());
  }

  @Test
  void nullAndEmptyStayDistinctOnTheWire() {
    // hash = empty byte string (40), summary value null
    byte[] empty = HexConverter.decode("d9985a8a010000f640f600f600f6");
    InputRecord emptyHash = InputRecord.fromCbor(empty);
    assertArrayEquals(new byte[0], emptyHash.getHash());
    assertArrayEquals(empty, emptyHash.toCbor());

    InputRecord nullHash = InputRecord.fromCbor(HexConverter.decode(EMPTY_TREE));
    assertNotEquals(emptyHash, nullHash);
    assertNotEquals(emptyHash.hashCode(), nullHash.hashCode());
    assertNotEquals(HexConverter.encode(emptyHash.toCbor()), HexConverter.encode(nullHash.toCbor()));
  }

  @Test
  void nullAndEmptySummaryValueAreDistinct() {
    byte[] empty = HexConverter.decode("d9985a8a010000f6f640 00f600f6".replace(" ", ""));
    InputRecord record = InputRecord.fromCbor(empty);
    assertArrayEquals(new byte[0], record.getSummaryValue());
    assertArrayEquals(empty, record.toCbor());
  }

  @Test
  void anIndefiniteOrNonCanonicalFieldIsRejected() {
    // hash as an indefinite length byte string
    assertThrows(CborSerializationException.class, () -> InputRecord.fromCbor(
        HexConverter.decode("d9985a8a010000f65f4100ff f600f600f6".replace(" ", ""))));
    // trailing data
    assertThrows(CborSerializationException.class,
        () -> InputRecord.fromCbor(HexConverter.decode(EMPTY_TREE + "00")));
  }

  @Test
  void unsignedFieldsKeepTheirBitsAndExposeTheNumber() {
    // round 2^63, epoch 2^64-1, timestamp 2^63+1, fees 2^64-1
    byte[] bytes = HexConverter.decode("d9985a8a01" + "1b8000000000000000" + "1bffffffffffffffff"
        + "f6" + "f6" + "f6" + "1b8000000000000001" + "f6" + "1bffffffffffffffff" + "f6");
    InputRecord record = InputRecord.fromCbor(bytes);

    assertEquals(Long.MIN_VALUE, record.getRoundNumber());
    assertEquals(BigInteger.ONE.shiftLeft(63), record.getRoundNumberUnsigned());
    assertEquals("9223372036854775808", record.getRoundNumberDecimal());
    assertEquals(-1L, record.getEpoch());
    assertEquals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), record.getEpochUnsigned());
    assertEquals("18446744073709551615", record.getEpochDecimal());
    assertEquals("9223372036854775809", record.getTimestampDecimal());
    assertEquals("18446744073709551615", record.getSumOfEarnedFeesDecimal());
    assertArrayEquals(bytes, record.toCbor());
  }

  @Test
  void structureRulesAreConditional() {
    // round 1 without a summary value
    InputRecord noSummary = InputRecord.fromCbor(HexConverter.decode(
        "d9985a8a010100f6" + "f6" + "f6" + "01" + "f6" + "00" + "f6"));
    assertEquals("The summary value is nil.", noSummary.structureFailure());

    // round 1 without a timestamp
    InputRecord noTimestamp = InputRecord.fromCbor(HexConverter.decode(
        "d9985a8a010100f6" + "f6" + "40" + "00" + "f6" + "00" + "f6"));
    assertEquals("The timestamp is unassigned.", noTimestamp.structureFailure());

    // state changed (null -> hash) without a block hash
    String hash = "5820" + "11".repeat(32);
    InputRecord changed = InputRecord.fromCbor(HexConverter.decode(
        "d9985a8a010000f6" + hash + "40" + "00" + "f6" + "00" + "f6"));
    assertEquals("The block hash is nil but the state hash changed.", changed.structureFailure());

    // unchanged state (null == empty) with a block hash
    InputRecord unchanged = InputRecord.fromCbor(HexConverter.decode(
        "d9985a8a010000f640f600" + "4101" + "00" + "f6"));
    assertEquals("The state hash did not change but the block hash is not nil.",
        unchanged.structureFailure());
    assertTrue(InputRecord.fromCbor(HexConverter.decode(EMPTY_TREE)).structureFailure() == null);
  }
}
