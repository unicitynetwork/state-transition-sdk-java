package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;
import org.unicitylabs.sdk.util.HexConverter;

class UnicitySealTest {

  private static byte[] seal(long networkId, byte[] signatureMap) {
    return CborSerializer.encodeTag(UnicitySeal.CBOR_TAG, CborSerializer.encodeArray(
        CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(networkId),
        CborSerializer.encodeUnsignedInteger(-1L),
        CborSerializer.encodeUnsignedInteger(Long.MIN_VALUE),
        CborSerializer.encodeUnsignedInteger(1_755_000_000L),
        CborSerializer.encodeNull(),
        CborSerializer.encodeByteString(new byte[32]),
        signatureMap));
  }

  private static byte[] map(String signer, byte[] witness) {
    return CborSerializer.encodeMap(new CborMap(java.util.Set.of(
        new CborMap.Entry(CborSerializer.encodeTextString(signer),
            CborSerializer.encodeByteString(witness)))));
  }

  @Test
  void aMalformedWitnessIsCarriedAndReEncodedUnchangedButNeverListedAsASignature() {
    byte[] witness = new byte[] {1, 2, 3};
    byte[] bytes = seal(3, map("node", witness));
    UnicitySeal decoded = UnicitySeal.fromCbor(bytes);

    assertArrayEquals(bytes, decoded.toCbor());
    assertArrayEquals(witness, decoded.getWitnesses().get("node"));
    assertTrue(decoded.getSignatures().isEmpty());
  }

  @Test
  void aWitnessWithAnOutOfRangeRecoveryIdIsCarriedToo() {
    byte[] witness = new byte[65];
    witness[64] = 4;
    byte[] bytes = seal(3, map("node", witness));
    UnicitySeal decoded = UnicitySeal.fromCbor(bytes);
    assertArrayEquals(bytes, decoded.toCbor());
    assertTrue(decoded.getSignatures().isEmpty());
    assertEquals(1, decoded.getWitnesses().size());
  }

  @Test
  void aSealOnTheWireNeedsItsSignatureMap() {
    assertThrows(CborSerializationException.class,
        () -> UnicitySeal.fromCbor(seal(3, CborSerializer.encodeNull())));
  }

  @Test
  void aDuplicateSignerIsRejected() {
    byte[] entry = CborSerializer.encodeTextString("node");
    byte[] value = CborSerializer.encodeByteString(new byte[65]);
    byte[] duplicated = new byte[] {(byte) 0xa2};
    byte[] map = concat(duplicated, entry, value, entry, value);
    assertThrows(CborSerializationException.class, () -> UnicitySeal.fromCbor(seal(3, map)));
  }

  @Test
  void anInvalidNetworkIdIsACodecErrorNotAnIllegalArgument() {
    assertThrows(CborSerializationException.class,
        () -> UnicitySeal.fromCbor(seal(0, map("node", new byte[65]))));
  }

  @Test
  void roundsEpochsAndTimestampsAreUnsigned() {
    UnicitySeal decoded = UnicitySeal.fromCbor(seal(3, map("node", new byte[65])));
    assertEquals(-1L, decoded.getRootChainRoundNumber());
    assertEquals("18446744073709551615", decoded.getRootChainRoundNumberDecimal());
    assertEquals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
        decoded.getRootChainRoundNumberUnsigned());
    assertEquals("9223372036854775808", decoded.getEpochDecimal());
    assertEquals(BigInteger.ONE.shiftLeft(63), decoded.getEpochUnsigned());
    assertEquals("1755000000", decoded.getTimestampDecimal());
    assertEquals(NetworkId.LOCAL, decoded.getNetworkId());
  }

  @Test
  void theSignedHashIgnoresTheWitnessesButEqualityDoesNot() {
    TestChain.Authority authority = new TestChain.Authority();
    UnicitySeal one = authority.seal(5, new byte[32], 0, 1, 2);
    UnicitySeal other = authority.seal(5, new byte[32], 1, 2, 3);
    assertArrayEquals(one.toCborWithoutSignatures(), other.toCborWithoutSignatures());
    assertTrue(!one.equals(other));
    assertEquals(one, UnicitySeal.fromCbor(one.toCbor()));
    assertNotNull(one.toString());
    assertNull(UnicitySeal.fromCbor(one.toCbor()).withSignatures(null).getWitnesses());
  }

  @Test
  void theWitnessesOfASealAreIndependentCopies() {
    TestChain.Authority authority = new TestChain.Authority();
    UnicitySeal seal = authority.seal(5, new byte[32], 0, 1, 2);
    Map<String, byte[]> witnesses = seal.getWitnesses();
    witnesses.values().iterator().next()[0] ^= 0x55;
    witnesses.clear();
    assertEquals(3, seal.getWitnesses().size());
    assertEquals(seal, UnicitySeal.fromCbor(seal.toCbor()));
  }

  private static byte[] concat(byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) {
      length += part.length;
    }
    byte[] result = new byte[length];
    int offset = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, result, offset, part.length);
      offset += part.length;
    }
    return result;
  }

  @SuppressWarnings("unused")
  private static String hex(byte[] bytes) {
    return HexConverter.encode(bytes);
  }
}
