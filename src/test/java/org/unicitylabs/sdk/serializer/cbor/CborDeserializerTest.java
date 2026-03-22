package org.unicitylabs.sdk.serializer.cbor;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer.CborTag;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap.Entry;
import org.unicitylabs.sdk.util.HexConverter;

public class CborDeserializerTest {

  @Test
  void testReadUnsignedInteger() {
    Assertions.assertEquals(
        5,
        CborDeserializer.decodeUnsignedInteger(HexConverter.decode("05")).asLong()
    );

    Assertions.assertEquals(
        100,
        CborDeserializer.decodeUnsignedInteger(HexConverter.decode("1864")).asLong()
    );

    Assertions.assertEquals(
        10000,
        CborDeserializer.decodeUnsignedInteger(HexConverter.decode("192710")).asLong()
    );

    Assertions.assertEquals(
        66000,
        CborDeserializer.decodeUnsignedInteger(HexConverter.decode("1a000101d0")).asLong()
    );

    Assertions.assertEquals(
        8147483647L,
        CborDeserializer.decodeUnsignedInteger(HexConverter.decode("1b00000001e5a0bbff")).asLong()
    );

    Assertions.assertEquals(
        -5,
        CborDeserializer.decodeUnsignedInteger(HexConverter.decode("1bfffffffffffffffb")).asLong()
    );
  }

  @Test
  void testReadByteString() {
    Assertions.assertArrayEquals(
        new byte[5],
        CborDeserializer.decodeByteString(HexConverter.decode("450000000000"))
    );

    Assertions.assertArrayEquals(
        new byte[25],
        CborDeserializer.decodeByteString(
            HexConverter.decode("581900000000000000000000000000000000000000000000000000"))
    );
  }

  @Test
  void testReadTextString() {
    Assertions.assertEquals(
        "Hello, world!",
        CborDeserializer.decodeTextString(HexConverter.decode("6d48656c6c6f2c20776f726c6421"))
    );

    Assertions.assertEquals(
        new String(new byte[25]),
        CborDeserializer.decodeTextString(
            HexConverter.decode("781900000000000000000000000000000000000000000000000000"))
    );
  }

  @Test
  void testReadArray() {
    List<byte[]> data = CborDeserializer.decodeArray(
        HexConverter.decode(
            "98196d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c6421")
    );

    for (byte[] item : data) {
      Assertions.assertEquals("Hello, world!", CborDeserializer.decodeTextString(item));
    }
  }

  @Test
  void testReadMap() {
    Set<CborMap.Entry> data = CborDeserializer.decodeMap(
        HexConverter.decode(
            "a4430000006d48656c6c6f2c20776f726c6421430000016d48656c6c6f2c20776f726c64216454657374f66d48656c6c6f2c20776f726c6421581900000000000000000000000000000000000000000000000000")
    );

    Iterator<Entry> iterator = data.iterator();
    Entry entry = iterator.next();
    Assertions.assertArrayEquals(
        CborSerializer.encodeByteString(HexConverter.decode("000000")),
        entry.getKey()
    );
    Assertions.assertArrayEquals(
        CborSerializer.encodeTextString("Hello, world!"),
        entry.getValue()
    );

    entry = iterator.next();
    Assertions.assertArrayEquals(
        CborSerializer.encodeByteString(HexConverter.decode("000001")),
        entry.getKey()
    );
    Assertions.assertArrayEquals(
        CborSerializer.encodeTextString("Hello, world!"),
        entry.getValue()
    );

    entry = iterator.next();
    Assertions.assertArrayEquals(
        CborSerializer.encodeTextString("Test"),
        entry.getKey()
    );
    Assertions.assertArrayEquals(
        CborSerializer.encodeNull(),
        entry.getValue()
    );

    entry = iterator.next();
    Assertions.assertArrayEquals(
        CborSerializer.encodeTextString("Hello, world!"),
        entry.getKey()
    );
    Assertions.assertArrayEquals(
        CborSerializer.encodeByteString(new byte[25]),
        entry.getValue()
    );
  }

  @Test
  void testReadBoolean() {
    Assertions.assertTrue(CborDeserializer.decodeBoolean(HexConverter.decode("f5")));

    Assertions.assertFalse(CborDeserializer.decodeBoolean(HexConverter.decode("f4")));
  }

  @Test
  void testReadOptional() {
    Assertions.assertNull(
        CborDeserializer.decodeNullable(
            HexConverter.decode("f6"),
            CborDeserializer::decodeUnsignedInteger
        )
    );
  }

  @Test
  void testEncodeTag() {
    CborTag tag = CborDeserializer.decodeTag(
        HexConverter.decode("d4781a746167206e756d62657220736d616c6c6572207468616e203234")
    );
    Assertions.assertEquals(
        20,
        tag.getTag()
    );

    Assertions.assertArrayEquals(
        CborSerializer.encodeTextString("tag number smaller than 24"),
        tag.getData()
    );
  }
}
