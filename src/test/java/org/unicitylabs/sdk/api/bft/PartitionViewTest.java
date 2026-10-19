package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.bft.PartitionViewException.Reason;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

class PartitionViewTest {

  private static final long NOW = TestChain.TIMESTAMP;

  private static PartitionView view() {
    return PartitionView.of(7, TestChain.fourShards(3, NOW));
  }

  private static Reason reason(byte[] bytes) {
    return assertThrows(PartitionViewException.class, () -> PartitionView.fromCbor(bytes))
        .getReason();
  }

  private static byte[] viewWith(byte[] version, byte[] partition, byte[] leaves) {
    return CborSerializer.encodeArray(version, partition, leaves);
  }

  private static byte[] leavesOf(PartitionView view) {
    byte[][] encoded = new byte[view.getLeaves().size()][];
    for (int i = 0; i < encoded.length; i++) {
      encoded[i] = view.getLeaves().get(i).toCbor();
    }
    return CborSerializer.encodeArray(encoded);
  }

  @Test
  void aViewRoundTripsByteForByte() {
    PartitionView view = view();
    byte[] bytes = view.toCbor();
    PartitionView decoded = PartitionView.fromCbor(bytes);
    assertEquals(view, decoded);
    assertArrayEquals(bytes, decoded.toCbor());
    assertEquals(7, decoded.getPartitionId());
    assertEquals(1, decoded.getVersion());
    assertEquals(4, decoded.getLeaves().size());
    assertEquals(4, decoded.getScheme().size());
  }

  @Test
  void aSingleShardViewHasAnEmptyScheme() {
    PartitionView single = PartitionView.of(7, List.of(TestChain.leaf("", 1, NOW, 1)));
    assertEquals(0, single.getScheme().size());
    assertEquals(single, PartitionView.fromCbor(single.toCbor()));
  }

  @Test
  void nullAndEmptyInTheInputRecordsOfALeafStayDistinct() {
    byte[] hash = new byte[32];
    InputRecord nullExecuted = new InputRecord(1, 1, null, hash, new byte[8], NOW, new byte[32],
        0, null);
    InputRecord emptyExecuted = new InputRecord(1, 1, null, hash, new byte[8], NOW, new byte[32],
        0, new byte[0]);
    PartitionViewLeaf a = new PartitionViewLeaf(ShardId.empty(), nullExecuted, new byte[32],
        new byte[32]);
    PartitionViewLeaf b = new PartitionViewLeaf(ShardId.empty(), emptyExecuted, new byte[32],
        new byte[32]);
    PartitionView viewA = PartitionView.of(7, List.of(a));
    PartitionView viewB = PartitionView.of(7, List.of(b));
    assertNotEquals(HexConverter.encode(viewA.toCbor()), HexConverter.encode(viewB.toCbor()));
    assertNull(PartitionView.fromCbor(viewA.toCbor()).getLeaves().get(0).getInputRecord()
        .getExecutedTransactionsHash());
    assertArrayEquals(new byte[0], PartitionView.fromCbor(viewB.toCbor()).getLeaves().get(0)
        .getInputRecord().getExecutedTransactionsHash());
    assertArrayEquals(viewB.toCbor(), PartitionView.fromCbor(viewB.toCbor()).toCbor());
  }

  @Test
  void theSizeLimitIsCheckedBeforeAnyDecoding() {
    assertEquals(Reason.BOUNDS, reason(new byte[PartitionView.MAX_BYTES + 1]));
    // at the limit the decoder looks at the bytes (and finds garbage)
    assertEquals(Reason.ENCODING, reason(new byte[PartitionView.MAX_BYTES]));
  }

  @Test
  void theLeafCountIsCheckedBeforeAnyLeafIsRead() {
    // nine items that are not leaves at all: were a leaf decoded first, the fault would be an
    // ENCODING one; the declared count alone is already out of bounds
    byte[][] items = new byte[9][];
    Arrays.fill(items, CborSerializer.encodeUnsignedInteger(0));
    assertEquals(Reason.BOUNDS, reason(viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(7), CborSerializer.encodeArray(items))));
    assertEquals(Reason.BOUNDS, reason(viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(7), new byte[] {(byte) 0x80})));
    // a count that cannot be satisfied by the bytes there are is a truncation
    assertEquals(Reason.ENCODING, reason(viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(7), HexConverter.decode("9bffffffffffffffff"))));
  }

  @Test
  void theOuterArrayHasExactlyThreeElements() {
    assertEquals(Reason.ENCODING, reason(CborSerializer.encodeArray(
        CborSerializer.encodeUnsignedInteger(1), CborSerializer.encodeUnsignedInteger(7))));
    assertEquals(Reason.ENCODING, reason(CborSerializer.encodeArray(
        CborSerializer.encodeUnsignedInteger(1), CborSerializer.encodeUnsignedInteger(7),
        leavesOf(view()), CborSerializer.encodeUnsignedInteger(0))));
    assertEquals(Reason.ENCODING, reason(HexConverter.decode("9f010780ff")));
    assertEquals(Reason.ENCODING, reason(new byte[0]));
    assertEquals(Reason.ENCODING, reason(CborSerializer.encodeUnsignedInteger(1)));
  }

  @Test
  void versionAndPartitionAreChecked() {
    byte[] leaves = leavesOf(view());
    assertEquals(Reason.ENCODING, reason(viewWith(CborSerializer.encodeUnsignedInteger(2),
        CborSerializer.encodeUnsignedInteger(7), leaves)));
    assertEquals(Reason.ENCODING, reason(viewWith(CborSerializer.encodeUnsignedInteger(0),
        CborSerializer.encodeUnsignedInteger(7), leaves)));
    assertEquals(Reason.BOUNDS, reason(viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(0), leaves)));
    assertEquals(Reason.BOUNDS, reason(viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(1L << 32), leaves)));
    assertEquals(Reason.BOUNDS, reason(viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(-1L), leaves)));
    // the largest partition id is a valid candidate: it is refused later, as another partition
    assertEquals(0xFFFFFFFFL, PartitionView.fromCbor(viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(0xFFFFFFFFL), leaves)).getPartitionId());
  }

  @Test
  void trailingDataTruncationAndNonMinimalIntegersAreRejected() {
    byte[] good = view().toCbor();
    byte[] trailing = Arrays.copyOf(good, good.length + 1);
    assertEquals(Reason.ENCODING, reason(trailing));
    assertEquals(Reason.ENCODING, reason(Arrays.copyOf(good, good.length - 1)));
    // version 1 as 0x1801
    byte[] nonMinimal = new byte[good.length + 1];
    System.arraycopy(good, 0, nonMinimal, 0, 1);
    nonMinimal[1] = 0x18;
    nonMinimal[2] = 0x01;
    System.arraycopy(good, 2, nonMinimal, 3, good.length - 2);
    assertEquals(Reason.ENCODING, reason(nonMinimal));
  }

  @Test
  void anOversizedInputRecordIsRefusedBeforeItIsDecoded() {
    // an item that is not an input record at all, but longer than the limit
    byte[] garbage = CborSerializer.encodeByteString(new byte[PartitionView.MAX_INPUT_RECORD_BYTES + 1]);
    byte[] leaf = CborSerializer.encodeArray(CborSerializer.encodeByteString(new byte[] {(byte) 0x80}),
        garbage, CborSerializer.encodeByteString(new byte[32]),
        CborSerializer.encodeByteString(new byte[32]));
    byte[] bytes = viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(7), CborSerializer.encodeArray(leaf));
    assertEquals(Reason.BOUNDS, reason(bytes));
  }

  @Test
  void aShardIdIsBoundedBeforeItIsDecoded() {
    byte[] hash = CborSerializer.encodeByteString(new byte[32]);
    byte[] record = TestChain.leaf("", 1, NOW, 1).getInputRecord().toCbor();
    // 34 bytes: more than 256 bits plus the end marker
    byte[] tooLong = CborSerializer.encodeArray(
        CborSerializer.encodeByteString(new byte[34]), record, hash, hash);
    assertEquals(Reason.BOUNDS, reason(viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(7), CborSerializer.encodeArray(tooLong))));
    // 257 bits fit the byte bound and are refused as bits
    byte[] bits257 = ShardId.fromBitString("1".repeat(257)).encode();
    byte[] leaf = CborSerializer.encodeArray(CborSerializer.encodeByteString(bits257), record, hash,
        hash);
    assertEquals(Reason.BOUNDS, reason(viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(7), CborSerializer.encodeArray(leaf))));
    // no end marker at all
    byte[] noMarker = CborSerializer.encodeArray(CborSerializer.encodeByteString(new byte[2]),
        record, hash, hash);
    assertEquals(Reason.ENCODING, reason(viewWith(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(7), CborSerializer.encodeArray(noMarker))));
  }

  @Test
  void leafBoundsCoverHashesSummaryAndStructure() {
    InputRecord ok = TestChain.leaf("", 1, NOW, 1).getInputRecord();
    byte[] t = new byte[32];
    // a technical record hash and a configuration hash of 31 bytes
    assertEquals(Reason.BOUNDS, assertThrows(PartitionViewException.class,
        () -> PartitionView.of(7, List.of(new PartitionViewLeaf(ShardId.empty(), ok, new byte[31], t))))
        .getReason());
    assertEquals(Reason.BOUNDS, assertThrows(PartitionViewException.class,
        () -> PartitionView.of(7, List.of(new PartitionViewLeaf(ShardId.empty(), ok, t, new byte[31]))))
        .getReason());
    // a summary value above 256 bytes
    InputRecord bigSummary = new InputRecord(1, 1, null, new byte[32], new byte[257], NOW,
        new byte[32], 0, null);
    assertEquals(Reason.BOUNDS, assertThrows(PartitionViewException.class,
        () -> PartitionView.of(7, List.of(new PartitionViewLeaf(ShardId.empty(), bigSummary, t, t))))
        .getReason());
    // a hash that is neither empty nor 32 bytes
    InputRecord shortHash = new InputRecord(1, 1, null, new byte[5], new byte[8], NOW, new byte[32],
        0, null);
    assertEquals(Reason.BOUNDS, assertThrows(PartitionViewException.class,
        () -> PartitionView.of(7, List.of(new PartitionViewLeaf(ShardId.empty(), shortHash, t, t))))
        .getReason());
    // round 1 with no timestamp
    InputRecord noTime = new InputRecord(1, 1, null, new byte[32], new byte[8], 0, new byte[32], 0,
        null);
    assertEquals(Reason.BOUNDS, assertThrows(PartitionViewException.class,
        () -> PartitionView.of(7, List.of(new PartitionViewLeaf(ShardId.empty(), noTime, t, t))))
        .getReason());
  }

  @Test
  void aViewHoldsOneToEightLeaves() {
    assertEquals(Reason.BOUNDS, assertThrows(PartitionViewException.class,
        () -> PartitionView.of(7, new ArrayList<>())).getReason());
    List<PartitionViewLeaf> nine = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      nine.add(TestChain.leaf("0".repeat(i), 1, NOW, 1));
    }
    assertEquals(Reason.BOUNDS,
        assertThrows(PartitionViewException.class, () -> PartitionView.of(7, nine)).getReason());
  }

  @Test
  void aViewIsImmutable() {
    PartitionView view = view();
    assertThrows(UnsupportedOperationException.class, () -> view.getLeaves().clear());
    assertThrows(UnsupportedOperationException.class, () -> view.getScheme().clear());
    byte[] hash = view.getLeaves().get(0).getShardConfigurationHash();
    hash[0] ^= 1;
    assertArrayEquals(view.getLeaves().get(0).getShardConfigurationHash(),
        PartitionView.fromCbor(view.toCbor()).getLeaves().get(0).getShardConfigurationHash());
  }

  @Test
  void roundsAndTimestampsAboveTwoToThe63SurviveTheCodec() {
    InputRecord record = new InputRecord(-1L, Long.MIN_VALUE, null, new byte[32], new byte[8], -1L,
        new byte[32], -2L, null);
    PartitionView view = PartitionView.of(7, List.of(new PartitionViewLeaf(ShardId.empty(), record,
        new byte[32], new byte[32])));
    InputRecord decoded = PartitionView.fromCbor(view.toCbor()).getLeaves().get(0).getInputRecord();
    assertEquals("18446744073709551615", decoded.getRoundNumberDecimal());
    assertEquals("9223372036854775808", decoded.getEpochDecimal());
    assertEquals("18446744073709551615", decoded.getTimestampDecimal());
    assertEquals("18446744073709551614", decoded.getSumOfEarnedFeesDecimal());
  }
}
