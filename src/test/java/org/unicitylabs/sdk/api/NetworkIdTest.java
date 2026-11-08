package org.unicitylabs.sdk.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

public class NetworkIdTest {

  @Test
  void rejectsZero() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> NetworkId.fromId((short) 0));
  }

  @Test
  void exposesUnsignedIdAboveSignedShortRange() {
    NetworkId networkId = NetworkId.fromId((short) 0x8000);
    Assertions.assertEquals(0x8000, networkId.getId());
    Assertions.assertEquals(0xFFFF, NetworkId.fromId((short) 0xFFFF).getId());
    Assertions.assertEquals(NetworkId.fromId((short) 0x8000), networkId);
  }

  @Test
  void cborRoundTripPreservesIdsAboveSignedShortRange() {
    for (int id : new int[] {1, 3, 0x7FFF, 0x8000, 0xC000, 0xFFFF}) {
      NetworkId networkId = NetworkId.fromId((short) id);

      byte[] encoded = CborSerializer.encodeUnsignedInteger(networkId.getId());
      NetworkId decoded =
              NetworkId.fromId(CborDeserializer.decodeUnsignedInteger(encoded).asShort());

      Assertions.assertEquals(id, decoded.getId());
      Assertions.assertEquals(networkId, decoded);
    }
  }

  @Test
  void jsonRoundTripPreservesIdsAboveSignedShortRange() throws Exception {
    NetworkId networkId = NetworkId.fromId((short) 0x8000);

    String json = UnicityObjectMapper.JSON.writeValueAsString(networkId);
    Assertions.assertEquals("32768", json);

    NetworkId decoded = UnicityObjectMapper.JSON.readValue(json, NetworkId.class);
    Assertions.assertEquals(networkId, decoded);
    Assertions.assertEquals(0x8000, decoded.getId());
  }

  @Test
  void jsonRejectsIdsOutsideUnsignedShortRange() {
    Assertions.assertThrows(
            Exception.class,
            () -> UnicityObjectMapper.JSON.readValue("70000", NetworkId.class));
    Assertions.assertThrows(
            Exception.class,
            () -> UnicityObjectMapper.JSON.readValue("0", NetworkId.class));
  }

  @Test
  void namedNetworksAreMainnetTestnet2AndLocal() {
    Assertions.assertSame(NetworkId.MAINNET, NetworkId.fromId((short) 1));
    Assertions.assertSame(NetworkId.LOCAL, NetworkId.fromId((short) 3));
    Assertions.assertSame(NetworkId.TESTNET2, NetworkId.fromId((short) 4));
    Assertions.assertEquals(4, NetworkId.TESTNET2.getId());
    Assertions.assertEquals("NetworkId[TESTNET2]", NetworkId.TESTNET2.toString());
  }

  @Test
  void theOldTestnetHasNoNameButTwoIsStillAValidId() {
    NetworkId two = NetworkId.fromId((short) 2);
    Assertions.assertEquals(2, two.getId());
    Assertions.assertEquals("NetworkId[2]", two.toString());
    Assertions.assertNotEquals(NetworkId.TESTNET2, two);
    for (java.lang.reflect.Field field : NetworkId.class.getFields()) {
      Assertions.assertNotEquals("TESTNET", field.getName());
    }
  }
}
