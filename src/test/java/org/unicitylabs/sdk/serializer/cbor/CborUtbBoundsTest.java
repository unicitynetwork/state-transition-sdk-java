package org.unicitylabs.sdk.serializer.cbor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;

/** Bounds and strictness the trust-base codec relies on. */
class CborUtbBoundsTest {

  @Test
  void textStringIsStrictUtf8() {
    byte[] valid = CborSerializer.encodeTextString("héllo €");
    assertEquals("héllo €", CborDeserializer.decodeTextString(valid));
    // 0xff is never valid UTF-8; a lone continuation byte; an overlong NUL; a surrogate half
    for (byte[] bad : new byte[][] {{(byte) 0xff}, {(byte) 0x80}, {(byte) 0xc0, (byte) 0x80},
        {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xe2, (byte) 0x82}}) {
      byte[] cbor = new byte[1 + bad.length];
      cbor[0] = (byte) (0x60 | bad.length);
      System.arraycopy(bad, 0, cbor, 1, bad.length);
      assertThrows(CborSerializationException.class, () -> CborDeserializer.decodeTextString(cbor));
    }
  }

  @Test
  void anArrayOverTheBoundIsRefusedFromItsHeaderBeforeAnyElementIsRead() {
    // a header declaring 2^32-1 elements and no body: refused by count, not by "premature end"
    byte[] header = {(byte) 0x9a, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
    CborSerializationException e = assertThrows(CborSerializationException.class,
        () -> CborDeserializer.decodeArrayAtMost(header, 256));
    assertEquals("Array of more than 256 elements.", e.getMessage());

    byte[] three = CborSerializer.encodeArray(CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(2), CborSerializer.encodeUnsignedInteger(3));
    assertEquals(3, CborDeserializer.decodeArrayAtMost(three, 3).size());
    CborSerializationException over = assertThrows(CborSerializationException.class,
        () -> CborDeserializer.decodeArrayAtMost(three, 2));
    assertEquals("Array of more than 2 elements.", over.getMessage());
  }

  @Test
  void aMapOverTheBoundIsRefusedFromItsHeaderAndKeepsTheCanonicalRules() {
    byte[] header = {(byte) 0xba, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
    CborSerializationException e = assertThrows(CborSerializationException.class,
        () -> CborDeserializer.decodeMapAtMost(header, 256));
    assertEquals("Map of more than 256 pairs.", e.getMessage());

    Set<CborMap.Entry> two = new HashSet<>(List.of(
        new CborMap.Entry(CborSerializer.encodeTextString("a"), CborSerializer.encodeNull()),
        new CborMap.Entry(CborSerializer.encodeTextString("b"), CborSerializer.encodeNull())));
    byte[] encoded = CborSerializer.encodeMap(new CborMap(two));
    assertEquals(2, CborDeserializer.decodeMapAtMost(encoded, 2).size());
    assertThrows(CborSerializationException.class,
        () -> CborDeserializer.decodeMapAtMost(encoded, 1));
    // keys out of canonical order are still refused
    byte[] unordered = {(byte) 0xa2, 0x61, 0x62, (byte) 0xf6, 0x61, 0x61, (byte) 0xf6};
    assertThrows(CborSerializationException.class,
        () -> CborDeserializer.decodeMapAtMost(unordered, 2));
    assertArrayEquals(encoded, CborSerializer.encodeMap(new CborMap(two)));
  }
}
