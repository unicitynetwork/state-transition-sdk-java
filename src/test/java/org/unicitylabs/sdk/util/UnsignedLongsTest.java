package org.unicitylabs.sdk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class UnsignedLongsTest {

  private static final long TWO_POW_63 = Long.MIN_VALUE;
  private static final long MAX = -1L;

  @Test
  void comparesAsUnsigned() {
    assertTrue(UnsignedLongs.lessThan(Long.MAX_VALUE, TWO_POW_63));
    assertTrue(UnsignedLongs.lessThan(TWO_POW_63, MAX));
    assertFalse(UnsignedLongs.lessThan(MAX, 0));
    assertEquals(0, UnsignedLongs.compare(MAX, MAX));
  }

  @Test
  void rendersAsUnsigned() {
    assertEquals("9223372036854775808", UnsignedLongs.toDecimal(TWO_POW_63));
    assertEquals("18446744073709551615", UnsignedLongs.toDecimal(MAX));
    assertEquals(new BigInteger("18446744073709551615"), UnsignedLongs.toBigInteger(MAX));
    assertEquals(BigInteger.ONE.shiftLeft(63), UnsignedLongs.toBigInteger(TWO_POW_63));
  }

  @Test
  void convertsBackAndChecksRange() {
    assertEquals(MAX, UnsignedLongs.fromBigInteger(new BigInteger("18446744073709551615")));
    assertEquals(TWO_POW_63, UnsignedLongs.fromBigInteger(BigInteger.ONE.shiftLeft(63)));
    assertThrows(IllegalArgumentException.class,
        () -> UnsignedLongs.fromBigInteger(BigInteger.ONE.shiftLeft(64)));
    assertThrows(IllegalArgumentException.class,
        () -> UnsignedLongs.fromBigInteger(BigInteger.valueOf(-1)));
    assertEquals(MAX, UnsignedLongs.parseDecimal("18446744073709551615"));
    assertThrows(NumberFormatException.class, () -> UnsignedLongs.parseDecimal("18446744073709551616"));
  }
}
