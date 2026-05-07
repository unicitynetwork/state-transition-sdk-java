package org.unicitylabs.sdk.hash;

import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DataHasherTest {

  @Test
  public void testSha256WithUpdate() {
    DataHasher hasher = new DataHasher(HashAlgorithm.SHA256);
    assertEquals(HashAlgorithm.SHA256, hasher.getAlgorithm());

    hasher.update("hello".getBytes(StandardCharsets.UTF_8));
    DataHash hash = hasher.digest();

    assertEquals(HashAlgorithm.SHA256, hash.getAlgorithm());
    assertArrayEquals(
            new byte[]{
                    (byte) 0x2c, (byte) 0xf2, (byte) 0x4d, (byte) 0xba, (byte) 0x5f, (byte) 0xb0,
                    (byte) 0xa3, (byte) 0x0e,
                    (byte) 0x26, (byte) 0xe8, (byte) 0x3b, (byte) 0x2a, (byte) 0xc5, (byte) 0xb9,
                    (byte) 0xe2, (byte) 0x9e,
                    (byte) 0x1b, (byte) 0x16, (byte) 0x1e, (byte) 0x5c, (byte) 0x1f, (byte) 0xa7,
                    (byte) 0x42, (byte) 0x5e,
                    (byte) 0x73, (byte) 0x04, (byte) 0x33, (byte) 0x62, (byte) 0x93, (byte) 0x8b,
                    (byte) 0x98, (byte) 0x24
            },
            hash.getData()
    );
  }

  @Test
  public void testMultipleUpdates() {
    DataHasher hasher = new DataHasher(HashAlgorithm.SHA256);

    hasher.update("hel".getBytes(StandardCharsets.UTF_8));
    hasher.update("lo".getBytes(StandardCharsets.UTF_8));

    DataHash hash = hasher.digest();

    // Should produce same hash as "hello"
    assertArrayEquals(
            new byte[]{
                    (byte) 0x2c, (byte) 0xf2, (byte) 0x4d, (byte) 0xba, (byte) 0x5f, (byte) 0xb0,
                    (byte) 0xa3, (byte) 0x0e,
                    (byte) 0x26, (byte) 0xe8, (byte) 0x3b, (byte) 0x2a, (byte) 0xc5, (byte) 0xb9,
                    (byte) 0xe2, (byte) 0x9e,
                    (byte) 0x1b, (byte) 0x16, (byte) 0x1e, (byte) 0x5c, (byte) 0x1f, (byte) 0xa7,
                    (byte) 0x42, (byte) 0x5e,
                    (byte) 0x73, (byte) 0x04, (byte) 0x33, (byte) 0x62, (byte) 0x93, (byte) 0x8b,
                    (byte) 0x98, (byte) 0x24
            },
            hash.getData()
    );
  }
}