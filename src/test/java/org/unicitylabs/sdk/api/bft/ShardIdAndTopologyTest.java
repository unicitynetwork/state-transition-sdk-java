package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.bft.PartitionViewException.Reason;

class ShardIdAndTopologyTest {

  private static List<ShardId> ids(String... bits) {
    List<ShardId> result = new ArrayList<>();
    for (String bit : bits) {
      result.add(ShardId.fromBitString(bit));
    }
    return result;
  }

  private static long[] counter() {
    return new long[1];
  }

  @Test
  void theEmptyShardIdIsTheEndMarkerOnly() {
    assertArrayEquals(new byte[] {(byte) 0x80}, ShardId.empty().encode());
    assertEquals(ShardId.decode(new byte[] {(byte) 0x80}), ShardId.empty());
    assertEquals(ShardId.empty(), ShardId.fromBitString(""));
    assertSame(ShardId.empty(), ShardId.fromBitString(""));
    assertTrue(ShardId.empty().isEmpty());
    assertEquals(0, ShardId.empty().getLength());
    assertEquals("", ShardId.empty().toString());
  }

  @Test
  void bitStringsRoundTripThroughTheEncoding() {
    for (String bits : new String[] {"0", "1", "01", "10110", "01010101", "010101011",
        "1".repeat(256), "0".repeat(255)}) {
      ShardId id = ShardId.fromBitString(bits);
      assertEquals(bits, id.toString());
      assertEquals(bits.length(), id.getLength());
      assertEquals(id, ShardId.decode(id.encode()));
      assertArrayEquals(id.encode(), ShardId.decode(id.encode()).encode());
    }
    assertThrows(IllegalArgumentException.class, () -> ShardId.fromBitString("012"));
  }

  @Test
  void equalityIsByBitsAndLength() {
    assertFalse(ShardId.fromBitString("0").equals(ShardId.fromBitString("00")));
    assertFalse(ShardId.fromBitString("0").equals(ShardId.fromBitString("1")));
    assertEquals(ShardId.fromBitString("0110"), ShardId.fromBitString("0110"));
    assertEquals(ShardId.fromBitString("0110").hashCode(), ShardId.fromBitString("0110").hashCode());
  }

  @Test
  void treeOrderPutsAPrefixBeforeItsExtensionsAndZeroBeforeOne() {
    assertTrue(ShardId.compareTreeOrder(ShardId.fromBitString("0"), ShardId.fromBitString("00")) < 0);
    assertTrue(ShardId.compareTreeOrder(ShardId.fromBitString("00"), ShardId.fromBitString("01")) < 0);
    assertTrue(ShardId.compareTreeOrder(ShardId.fromBitString("011"), ShardId.fromBitString("1")) < 0);
    assertEquals(0, ShardId.compareTreeOrder(ShardId.fromBitString("101"), ShardId.fromBitString("101")));
    assertTrue(ShardId.fromBitString("10").isPrefixOf(ShardId.fromBitString("1011")));
    assertFalse(ShardId.fromBitString("11").isPrefixOf(ShardId.fromBitString("1011")));
    assertTrue(ShardId.empty().isPrefixOf(ShardId.fromBitString("1")));
  }

  @Test
  void aCompleteSchemeInCanonicalOrderNeedsLinearComparisons() {
    long[] comparisons = counter();
    List<ShardId> order = ShardTopology.canonicalOrder(
        ids("000", "001", "010", "011", "100", "101", "110", "111"), 8, 256, comparisons);
    assertEquals(ids("000", "001", "010", "011", "100", "101", "110", "111"), order);
    // 7 neighbours for sortedness and 7 for the prefix check: O(S)
    assertEquals(14, comparisons[0]);
  }

  @Test
  void anUnsortedSchemeIsSortedWithoutAPairwiseScan() {
    long[] comparisons = counter();
    List<ShardId> order = ShardTopology.canonicalOrder(
        ids("11", "00", "10", "01"), 8, 256, comparisons);
    assertEquals(ids("00", "01", "10", "11"), order);
    assertTrue(comparisons[0] < 4 * 4, "fewer comparisons than a pairwise scan");
  }

  @Test
  void anUnbalancedSchemeIsValidWhenItIsComplete() {
    assertEquals(ids("0", "10", "11"),
        ShardTopology.canonicalOrder(ids("11", "0", "10"), 8, 256, null));
  }

  @Test
  void theEmptySchemeIsTheSingleShardPartition() {
    assertEquals(Collections.singletonList(ShardId.empty()),
        ShardTopology.canonicalOrder(Collections.emptyList(), 8, 256, null));
  }

  @Test
  void invalidSchemesAreRefusedAsTopology() {
    for (List<ShardId> scheme : List.of(
        ids("0"),                       // a single shard is the empty scheme
        ids("0", "1", "10"),            // overlapping
        ids("0", "0"),                  // duplicate
        ids("00", "01", "10"),          // incomplete
        ids("", "0", "1"),              // the empty id inside a scheme
        ids("0", "1", "1"),             // duplicate leaf
        ids("000", "001", "01", "1", "00"))) { // 00 overlaps 000/001
      PartitionViewException error = assertThrows(PartitionViewException.class,
          () -> ShardTopology.canonicalOrder(scheme, 8, 256, null), scheme.toString());
      assertEquals(Reason.TOPOLOGY, error.getReason());
    }
  }

  @Test
  void limitsOfLeavesAndBitsAreEnforced() {
    List<ShardId> nine = ids("0000", "0001", "0010", "0011", "0100", "0101", "0110", "0111", "1");
    assertEquals(Reason.TOPOLOGY, assertThrows(PartitionViewException.class,
        () -> ShardTopology.canonicalOrder(nine, 8, 256, null)).getReason());
    assertEquals(Reason.TOPOLOGY, assertThrows(PartitionViewException.class,
        () -> ShardTopology.canonicalOrder(ids("0".repeat(257), "1"), 8, 256, null)).getReason());
  }

  @Test
  void aDeepButCompleteSchemeIsValid() {
    List<String> bits = new ArrayList<>();
    StringBuilder prefix = new StringBuilder();
    for (int depth = 0; depth < 7; depth++) {
      bits.add(prefix + "1");
      prefix.append('0');
    }
    bits.add(prefix.toString());
    assertEquals(8, ShardTopology.canonicalOrder(ids(bits.toArray(new String[0])), 8, 256, null)
        .size());
  }
}
