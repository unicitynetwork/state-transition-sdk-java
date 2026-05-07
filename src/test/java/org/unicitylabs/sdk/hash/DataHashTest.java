package org.unicitylabs.sdk.hash;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.util.HexConverter;

public class DataHashTest {

  @Test
  public void testInvalidDataHashArguments() {
    NullPointerException exception = Assertions.assertThrows(NullPointerException.class,
            () -> new DataHash(null, new byte[32]));
    Assertions.assertEquals("algorithm cannot be null", exception.getMessage());
    exception = Assertions.assertThrows(NullPointerException.class,
            () -> new DataHash(HashAlgorithm.SHA256, null));
    Assertions.assertEquals("data cannot be null", exception.getMessage());
  }

  @Test
  public void testDataHashCborSerialization() {
    Assertions.assertArrayEquals(
            HexConverter.decode("582200000000000000000000000000000000000000000000000000000000000000000000"),
            new DataHash(HashAlgorithm.SHA256, new byte[32]).toCbor()
    );

    Assertions.assertArrayEquals(
            HexConverter.decode("58320002000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"),
            new DataHash(HashAlgorithm.SHA384, new byte[48]).toCbor()
    );

    Assertions.assertEquals(
            new DataHash(HashAlgorithm.SHA256, new byte[32]),
            DataHash.fromCbor(HexConverter.decode("582200000000000000000000000000000000000000000000000000000000000000000000"))
    );
  }
}
