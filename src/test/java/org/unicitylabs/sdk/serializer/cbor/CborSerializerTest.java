package org.unicitylabs.sdk.serializer.cbor;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CborSerializerTest {

  @Test
  void testCborMap() {
    // Check that key cannot be null on entry
    Assertions.assertThrows(NullPointerException.class,
            () -> new CborMap.Entry(null, new byte[5]));
    // Check that value cannot be null on entry
    Assertions.assertThrows(NullPointerException.class,
            () -> new CborMap.Entry(new byte[5], null));
    // Do not allow null entries
    Assertions.assertThrows(NullPointerException.class, () -> new CborMap(null));
    // Check if duplicate keys are detected
    Assertions.assertThrows(IllegalArgumentException.class, () -> Set.of(
            new CborMap.Entry(new byte[5], new byte[5]),
            new CborMap.Entry(new byte[5], new byte[5])
    ));
  }

  @Test
  void testEncodeUnsignedInteger() {
    Assertions.assertArrayEquals(
            HexConverter.decode("05"),
            CborSerializer.encodeUnsignedInteger(5)
    );

    Assertions.assertArrayEquals(
            HexConverter.decode("1864"),
            CborSerializer.encodeUnsignedInteger(100)
    );

    Assertions.assertArrayEquals(
            HexConverter.decode("192710"),
            CborSerializer.encodeUnsignedInteger(10000)
    );

    Assertions.assertArrayEquals(
            HexConverter.decode("1a000101d0"),
            CborSerializer.encodeUnsignedInteger(66000)
    );

    Assertions.assertArrayEquals(
            HexConverter.decode("1b00000001e5a0bbff"),
            CborSerializer.encodeUnsignedInteger(8147483647L)
    );

    Assertions.assertArrayEquals(
            HexConverter.decode("1bfffffffffffffffb"),
            CborSerializer.encodeUnsignedInteger(-5)
    );
  }

  @Test
  void testEncodeByteString() {
    Assertions.assertArrayEquals(
            HexConverter.decode("450000000000"),
            CborSerializer.encodeByteString(new byte[5])
    );

    Assertions.assertArrayEquals(
            HexConverter.decode("581900000000000000000000000000000000000000000000000000"),
            CborSerializer.encodeByteString(new byte[25])
    );
  }

  @Test
  void testEncodeTextString() {
    Assertions.assertArrayEquals(
            HexConverter.decode("6d48656c6c6f2c20776f726c6421"),
            CborSerializer.encodeTextString("Hello, world!")
    );

    Assertions.assertArrayEquals(
            HexConverter.decode("781900000000000000000000000000000000000000000000000000"),
            CborSerializer.encodeTextString(new String(new byte[25]))
    );
  }

  @Test
  void testEncodeArray() {
    Assertions.assertArrayEquals(
            HexConverter.decode(
                    "826d48656c6c6f2c20776f726c6421581900000000000000000000000000000000000000000000000000"),
            CborSerializer.encodeArray(
                    CborSerializer.encodeTextString("Hello, world!"),
                    CborSerializer.encodeByteString(new byte[25])
            )
    );

    List<byte[]> list = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      list.add(CborSerializer.encodeTextString("Hello, world!"));
    }

    Assertions.assertArrayEquals(
            HexConverter.decode(
                    "98196d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c64216d48656c6c6f2c20776f726c6421"),
            CborSerializer.encodeArray(list.toArray(byte[][]::new))
    );
  }

  @Test
  void testEncodeMap() {
    Assertions.assertArrayEquals(
            HexConverter.decode(
                    "a4430000006d48656c6c6f2c20776f726c6421430000016d48656c6c6f2c20776f726c64216454657374f66d48656c6c6f2c20776f726c6421581900000000000000000000000000000000000000000000000000"),
            CborSerializer.encodeMap(
                    new CborMap(
                            Set.of(
                                    new CborMap.Entry(
                                            CborSerializer.encodeByteString(HexConverter.decode("000001")),
                                            CborSerializer.encodeTextString("Hello, world!")
                                    ),
                                    new CborMap.Entry(
                                            CborSerializer.encodeByteString(HexConverter.decode("000000")),
                                            CborSerializer.encodeTextString("Hello, world!")
                                    ),
                                    new CborMap.Entry(
                                            CborSerializer.encodeTextString("Hello, world!"),
                                            CborSerializer.encodeByteString(new byte[25])
                                    ),
                                    new CborMap.Entry(
                                            CborSerializer.encodeTextString("Test"),
                                            CborSerializer.encodeNull()
                                    )
                            )
                    )
            )
    );
  }

  @Test
  void testEncodeBoolean() {
    Assertions.assertArrayEquals(
            HexConverter.decode("f5"),
            CborSerializer.encodeBoolean(true)
    );

    Assertions.assertArrayEquals(
            HexConverter.decode("f4"),
            CborSerializer.encodeBoolean(false)
    );
  }

  @Test
  void testEncodeNull() {
    Assertions.assertArrayEquals(
            HexConverter.decode("f6"),
            CborSerializer.encodeNull()
    );
  }

  @Test
  void testEncodeTag() {
    Assertions.assertArrayEquals(
            HexConverter.decode("d4781a746167206e756d62657220736d616c6c6572207468616e203234"),
            CborSerializer.encodeTag(20, CborSerializer.encodeTextString("tag number smaller than 24"))
    );

    Assertions.assertArrayEquals(
            HexConverter.decode("d874706c6172676520746167206e756d626572"),
            CborSerializer.encodeTag(116, CborSerializer.encodeTextString("large tag number"))
    );

  }
}
