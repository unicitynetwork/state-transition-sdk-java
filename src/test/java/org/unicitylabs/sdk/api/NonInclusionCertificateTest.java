package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.util.HexConverter;

class NonInclusionCertificateTest {

  // two leaves 00..00 -> 01..01 and 80..00 -> 02..02, queried at 01..00 (rugregator golden vector)
  private static final String TWO_LEAF = "80" + "00".repeat(31)
      + "d79ae116bb8c8604819adc35c2a71b7c3acf89cd1be8a382fbedbd6905ac20e7"
      + "00".repeat(32) + "01".repeat(32);
  private static final byte[] TWO_LEAF_ROOT =
      HexConverter.decode("5158a5f4029511f5312b7a147c9da48b9281322d1b893a10a25b2c17e2197a53");

  private static byte[] key(int first, int last) {
    byte[] key = new byte[32];
    key[0] = (byte) first;
    key[31] = (byte) last;
    return key;
  }

  @Test
  void goldenTwoLeafCertificateRoundTripsAndAuthenticatesAbsence() {
    byte[] wire = HexConverter.decode(TWO_LEAF);
    NonInclusionCertificate certificate = NonInclusionCertificate.decode(wire);
    assertArrayEquals(wire, certificate.encode());
    byte[] query = key(0x01, 0);
    assertTrue(certificate.authenticates(query, TWO_LEAF_ROOT));
    assertFalse(certificate.terminalIs(query));
    // the query takes the other side of the junction: not authenticated
    assertFalse(certificate.authenticates(key(0x80, 1), TWO_LEAF_ROOT));
    // another root
    byte[] other = TWO_LEAF_ROOT.clone();
    other[0] ^= 1;
    assertFalse(certificate.authenticates(query, other));
    assertFalse(certificate.authenticates(query, null));
  }

  @Test
  void theEmptyCertificateMatchesOnlyTheEmptyRoot() {
    NonInclusionCertificate empty = NonInclusionCertificate.decode(new byte[0]);
    assertTrue(empty.isEmptyTree());
    assertEquals(0, empty.encode().length);
    assertTrue(empty.authenticates(key(1, 1), null));
    assertFalse(empty.authenticates(key(1, 1), TWO_LEAF_ROOT));
    assertFalse(empty.terminalIs(key(1, 1)));
    // a nonempty certificate never matches the empty root
    assertFalse(NonInclusionCertificate.decode(HexConverter.decode(TWO_LEAF))
        .authenticates(key(1, 0), null));
  }

  @Test
  void decodingIsExact() {
    byte[] valid = HexConverter.decode(TWO_LEAF);
    assertThrows(IllegalArgumentException.class,
        () -> NonInclusionCertificate.decode(new byte[31]));
    assertThrows(IllegalArgumentException.class,
        () -> NonInclusionCertificate.decode(new byte[32]));
    assertThrows(IllegalArgumentException.class,
        () -> NonInclusionCertificate.decode(java.util.Arrays.copyOf(valid, valid.length - 1)));
    assertThrows(IllegalArgumentException.class,
        () -> NonInclusionCertificate.decode(java.util.Arrays.copyOf(valid, valid.length + 1)));
    // a full-depth bitmap would need 8288 bytes; anything else with that bitmap is refused
    byte[] allBits = new byte[96];
    java.util.Arrays.fill(allBits, 0, 32, (byte) 0xff);
    assertThrows(IllegalArgumentException.class, () -> NonInclusionCertificate.decode(allBits));
  }

  @Test
  void snapshotKeepsUnsignedValuesLosslessly() {
    long twoPow63 = Long.MIN_VALUE;
    long max = -1L;
    NonInclusionSnapshot snapshot = new NonInclusionSnapshot(NetworkId.TESTNET, 7,
        ShardId.decode(new byte[] {(byte) 0x80}), twoPow63, max, max - 2, 0, max, new byte[32]);
    assertEquals("9223372036854775808", snapshot.getRootRoundDecimal());
    assertEquals("18446744073709551615", snapshot.getSealTimestampSecondsDecimal());
    assertEquals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.valueOf(3)),
        snapshot.getRootEpochUnsigned());
    assertEquals(BigInteger.ONE.shiftLeft(63), snapshot.getRootRoundUnsigned());
    assertEquals("0", snapshot.getShardRoundDecimal());
    // copies: mutating a returned root does not change the snapshot
    snapshot.getStateRoot()[0] = 1;
    assertEquals(0, snapshot.getStateRoot()[0]);
  }

  @Test
  void theDefaultContextIsUnshardedAndPinsAreCopied() {
    NonInclusionContext context = NonInclusionContext.unsharded();
    assertEquals(0, context.getShard().getLength());
    assertFalse(context.hasAuthorityEndRound());
    assertThrows(IllegalArgumentException.class,
        () -> context.withShardConfigurationHash(new byte[31]));
    byte[] pin = new byte[32];
    NonInclusionContext pinned = context.withShardConfigurationHash(pin);
    pin[0] = 1;
    assertEquals(0, pinned.getShardConfigurationHash()[0]);
    assertTrue(pinned.withAuthorityEndRound(-1L).hasAuthorityEndRound());
  }
}
