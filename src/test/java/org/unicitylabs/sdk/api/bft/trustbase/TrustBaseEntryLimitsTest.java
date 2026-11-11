package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/** Owner §49 bounds of an entry: 128 KiB, 256 members, 256 signatures, 128-byte node ids. */
class TrustBaseEntryLimitsTest {

  private static final JsonNode MAX = TrustBaseVectors.maxEntry();

  private static byte[] secret(int i) {
    byte[] secret = new byte[32];
    secret[30] = (byte) ((i + 1) >> 8);
    secret[31] = (byte) (i + 1);
    return secret;
  }

  private static byte[] key(int i) {
    return new SigningService(secret(i)).getPublicKey();
  }

  private static TrustBaseEntry genesis(int members) {
    List<TrustBaseMember> list = new ArrayList<>();
    for (int i = 0; i < members; i++) {
      list.add(new TrustBaseMember(String.format("n%04d", i), key(i), 1));
    }
    return new TrustBaseEntry(5, 1, 1, list, members * 2L / 3 + 1, null, null, new byte[32], null);
  }

  private static TrustBaseEntry successorWithSignatures(int signatures) {
    List<TrustBaseMember> one = List.of(new TrustBaseMember("a", key(0), 1));
    Map<String, byte[]> map = new HashMap<>();
    for (int i = 0; i < signatures; i++) {
      map.put(String.format("s%04d", i), new byte[65]);
    }
    return new TrustBaseEntry(5, 2, 10, one, 1, null, null, new byte[] {7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7}, map);
  }

  @Test
  void theMaximumEntryDecodesAndReEncodesWithTheSharedIdAndFitsTheBound() {
    assertEquals(128 * 1024, TrustBaseEntry.MAX_ENTRY_BYTES);
    assertEquals(95365, MAX.get("entryBytes").asInt());
    for (String field : new String[] {"genesis", "entry"}) {
      byte[] bytes = HexConverter.decode(MAX.get(field).asText());
      assertTrue(bytes.length <= MAX.get("entryBytes").asInt());
      TrustBaseEntry entry = TrustBaseEntry.decode(bytes);
      assertEquals(MAX.get("network").asInt(), entry.getNetworkId().getId());
      assertEquals(MAX.get("members").asInt(), entry.getMembers().size());
      for (TrustBaseMember member : entry.getMembers()) {
        assertEquals(MAX.get("nodeIdBytes").asInt(),
            member.getNodeId().getBytes(StandardCharsets.UTF_8).length);
      }
      assertArrayEquals(bytes, entry.encode());
      assertEquals(MAX.get(field.equals("genesis") ? "genesisId" : "entryId").asText(),
          HexConverter.encode(entry.entryId()));
    }
  }

  @Test
  void theMaximumEntryIsSignedByAllTheGenesisMembers() {
    TrustBaseEntry genesis = TrustBaseEntry.decode(HexConverter.decode(MAX.get("genesis").asText()));
    TrustBaseEntry entry = TrustBaseEntry.decode(HexConverter.decode(MAX.get("entry").asText()));
    assertEquals(256, entry.getSignatures().size());
    entry.verifySuccessor(genesis);
  }

  @Test
  void anEntryOverTheByteBoundIsRefusedBeforeItIsParsed() {
    byte[] valid = HexConverter.decode(MAX.get("entry").asText());
    byte[] atBound = Arrays.copyOf(valid, TrustBaseEntry.MAX_ENTRY_BYTES);
    byte[] overBound = Arrays.copyOf(valid, TrustBaseEntry.MAX_ENTRY_BYTES + 1);
    // at the bound the bytes are examined (and fail for trailing bytes), over it they are not
    TrustBaseEncodingException over =
        assertThrows(TrustBaseEncodingException.class, () -> TrustBaseEntry.decode(overBound));
    assertEquals("The entry exceeds the size bound.", over.getMessage());
    TrustBaseEncodingException at =
        assertThrows(TrustBaseEncodingException.class, () -> TrustBaseEntry.decode(atBound));
    assertTrue(!at.getMessage().contains("size bound"), at.getMessage());
  }

  @Test
  void twoHundredFiftySixMembersAreValidAndAnEntryOfTwoHundredFiftySevenIsRefused() {
    TrustBaseEntry at = genesis(256);
    assertEquals(at, TrustBaseEntry.decode(at.encode()));
    TrustBaseEntry over = genesis(257);
    TrustBaseEncodingException e =
        assertThrows(TrustBaseEncodingException.class, () -> TrustBaseEntry.decode(over.encode()));
    assertTrue(e.getMessage().contains("members"), e.getMessage());
  }

  @Test
  void validateRefusesAMemberCountOutsideOneTo256() {
    assertThrows(TrustBaseInvalidException.class, () -> genesis(257).validate());
    assertThrows(TrustBaseInvalidException.class, () -> genesis(0).validate());
  }

  @Test
  void anEntryCarriesAtMost256SignaturesHoweverSmallItIs() {
    TrustBaseEntry at = successorWithSignatures(256);
    assertEquals(at, TrustBaseEntry.decode(at.encode()));

    TrustBaseEntry over = successorWithSignatures(257);
    byte[] bytes = over.encode();
    assertTrue(bytes.length < TrustBaseEntry.MAX_ENTRY_BYTES, "inside the byte cap: the count refuses it");
    TrustBaseEncodingException e =
        assertThrows(TrustBaseEncodingException.class, () -> TrustBaseEntry.decode(bytes));
    assertTrue(e.getMessage().contains("pairs") || e.getMessage().contains("signatures"),
        e.getMessage());
    // the slack of a parser (272) is not an admission allowance
    byte[] slack = successorWithSignatures(272).encode();
    assertThrows(TrustBaseEncodingException.class, () -> TrustBaseEntry.decode(slack));
  }

  @Test
  void aSuppliedWitnessOf257SignaturesIsRefusedBeforeAnySignatureWork() {
    TrustBaseEntry committee = genesis(4);
    Map<String, byte[]> junk = new HashMap<>();
    for (int i = 0; i < 257; i++) {
      junk.put(String.format("n%04d", i % 4) + i, new byte[65]);
    }
    TrustBaseSignatureException over = assertThrows(TrustBaseSignatureException.class,
        () -> committee.verifyQuorumSignatures(new byte[] {1}, junk));
    assertEquals("Too many signatures.", over.getMessage());

    Map<String, byte[]> atCap = new HashMap<>();
    for (int i = 0; i < 256; i++) {
      atCap.put(String.format("n%04d", i % 4) + i, new byte[65]);
    }
    TrustBaseSignatureException reached = assertThrows(TrustBaseSignatureException.class,
        () -> committee.verifyQuorumSignatures(new byte[] {1}, atCap));
    assertTrue(!reached.getMessage().equals("Too many signatures."), reached.getMessage());
  }

  @Test
  void aNodeIdOf129BytesAndOfZeroBytesIsInvalid() {
    List<TrustBaseMember> long129 = List.of(
        new TrustBaseMember("x".repeat(129), key(0), 1));
    assertThrows(TrustBaseInvalidException.class, () -> new TrustBaseEntry(5, 1, 1, long129, 1,
        null, null, new byte[32], null).validate());
    List<TrustBaseMember> long128 = List.of(
        new TrustBaseMember("x".repeat(128), key(0), 1));
    new TrustBaseEntry(5, 1, 1, long128, 1, null, null, new byte[32], null).validate();
    List<TrustBaseMember> empty = List.of(new TrustBaseMember("", key(0), 1));
    assertThrows(TrustBaseInvalidException.class, () -> new TrustBaseEntry(5, 1, 1, empty, 1,
        null, null, new byte[32], null).validate());
  }

  @Test
  void aWeightOutsideOneToTwoToThe40IsInvalidWhateverTheThreshold() {
    // the threshold is the exact one for the weight, so only the weight rule can refuse the entry
    for (long weight : new long[] {0, (1L << 40) + 1}) {
      List<TrustBaseMember> one = List.of(new TrustBaseMember("a", key(0), weight));
      TrustBaseInvalidException e = assertThrows(TrustBaseInvalidException.class,
          () -> new TrustBaseEntry(5, 1, 1, one, (weight * 2) / 3 + 1, null, null, new byte[32],
              null).validate(), Long.toUnsignedString(weight));
      assertTrue(e.getMessage().contains("weight"), e.getMessage());
    }
    List<TrustBaseMember> negative = List.of(new TrustBaseMember("a", key(0), -1L));
    TrustBaseInvalidException e = assertThrows(TrustBaseInvalidException.class,
        () -> new TrustBaseEntry(5, 1, 1, negative, 1, null, null, new byte[32], null).validate());
    assertTrue(e.getMessage().contains("weight"), e.getMessage());
    List<TrustBaseMember> max = List.of(new TrustBaseMember("a", key(0), 1L << 40));
    new TrustBaseEntry(5, 1, 1, max, ((1L << 40) * 2) / 3 + 1, null, null, new byte[32], null)
        .validate();
  }

  @Test
  void aSignatureMustBeTheCanonical65ByteRecoverableForm() {
    TrustBaseEntry committee = genesis(4);
    byte[] data = new byte[] {1, 2, 3};
    DataHash hash = new DataHasher(HashAlgorithm.SHA256).update(data).digest();
    byte[] good = new SigningService(secret(0)).sign(hash).encode();
    assertEquals(65, good.length);
    assertEquals(1L, committee.verifySignature(data, good, "n0000"));

    // the 64-byte compact form of a valid signature
    assertThrows(TrustBaseSignatureException.class,
        () -> committee.verifySignature(data, Arrays.copyOf(good, 64), "n0000"));
    // recovery id outside 0..3
    byte[] badRecovery = good.clone();
    badRecovery[64] = 4;
    assertThrows(TrustBaseSignatureException.class,
        () -> committee.verifySignature(data, badRecovery, "n0000"));
    // a wrong recovery id recovers another key
    byte[] otherRecovery = good.clone();
    otherRecovery[64] = (byte) (good[64] ^ 1);
    assertThrows(TrustBaseSignatureException.class,
        () -> committee.verifySignature(data, otherRecovery, "n0000"));
    // the high-S twin of a valid signature
    java.math.BigInteger n = new java.math.BigInteger(
        "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16);
    java.math.BigInteger s = new java.math.BigInteger(1, Arrays.copyOfRange(good, 32, 64));
    byte[] twin = good.clone();
    byte[] highS = n.subtract(s).toByteArray();
    Arrays.fill(twin, 32, 64, (byte) 0);
    System.arraycopy(highS, Math.max(0, highS.length - 32), twin, 64 - Math.min(32, highS.length),
        Math.min(32, highS.length));
    twin[64] = (byte) (good[64] ^ 1);
    assertThrows(TrustBaseSignatureException.class,
        () -> committee.verifySignature(data, twin, "n0000"));
    // another member's key, and a stranger
    assertThrows(TrustBaseSignatureException.class,
        () -> committee.verifySignature(data, good, "n0001"));
    assertThrows(TrustBaseSignatureException.class,
        () -> committee.verifySignature(data, good, "nobody"));
  }

  @Test
  void aNonGenesisEntryHasItsPredecessorIdAndNullSignatureFieldInSigBytes() {
    TrustBaseEntry entry = TrustBaseEntry.decode(HexConverter.decode(MAX.get("entry").asText()));
    byte[] sigBytes = entry.sigBytes();
    // the signature field is the last element and is CBOR null in sigBytes
    assertEquals((byte) 0xf6, sigBytes[sigBytes.length - 1]);
    assertArrayEquals(CborSerializer.encodeNull(),
        Arrays.copyOfRange(sigBytes, sigBytes.length - 1, sigBytes.length));
  }

  private static TrustBaseEntry withMembers(TrustBaseMember... members) {
    return new TrustBaseEntry(5, 1, 1, List.of(members), members.length * 2L / 3 + 1, null, null,
        new byte[32], null);
  }

  @Test
  void membersAreSortedByRawUtf8BytesNotByJavaStringOrder() {
    // U+FF5E (EF BD 9E) sorts before U+1F600 (F0 9F 98 80) as bytes; String.compareTo says the opposite
    String bmp = "\uff5e";
    String astral = "\ud83d\ude00";
    assertTrue(bmp.compareTo(astral) > 0);
    withMembers(new TrustBaseMember(bmp, key(0), 1), new TrustBaseMember(astral, key(1), 1))
        .validate();
    assertThrows(TrustBaseInvalidException.class, () -> withMembers(
        new TrustBaseMember(astral, key(0), 1), new TrustBaseMember(bmp, key(1), 1)).validate());
  }

  @Test
  void duplicateOrUnsortedMemberIdsAreInvalid() {
    assertThrows(TrustBaseInvalidException.class, () -> withMembers(
        new TrustBaseMember("a", key(0), 1), new TrustBaseMember("a", key(1), 1)).validate());
    assertThrows(TrustBaseInvalidException.class, () -> withMembers(
        new TrustBaseMember("b", key(0), 1), new TrustBaseMember("a", key(1), 1)).validate());
  }

  @Test
  void twoMembersCannotShareASigningKey() {
    TrustBaseInvalidException e = assertThrows(TrustBaseInvalidException.class, () -> withMembers(
        new TrustBaseMember("a", key(0), 1), new TrustBaseMember("b", key(0), 1)).validate());
    assertTrue(e.getMessage().contains("share a signing key"), e.getMessage());
  }

  @Test
  void aKeyThatIsNotACompressedPointIsInvalid() {
    byte[] uncompressedPrefix = key(0);
    uncompressedPrefix[0] = 0x04;
    byte[] notOnCurve = new byte[33];
    notOnCurve[0] = 0x02;
    notOnCurve[32] = 5;
    for (byte[] bad : new byte[][] {uncompressedPrefix, notOnCurve, new byte[33],
        Arrays.copyOf(key(0), 32)}) {
      assertThrows(TrustBaseInvalidException.class,
          () -> withMembers(new TrustBaseMember("a", bad, 1)).validate());
    }
  }

  @Test
  void signaturesAreHeldInRawUtf8ByteOrder() {
    Map<String, byte[]> map = new HashMap<>();
    map.put("\ud83d\ude00", new byte[65]);
    map.put("\uff5e", new byte[65]);
    map.put("a", new byte[65]);
    TrustBaseEntry entry = new TrustBaseEntry(5, 2, 10,
        List.of(new TrustBaseMember("a", key(0), 1)), 1, null, null, new byte[] {7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7}, map);
    assertEquals(List.of("a", "\uff5e", "\ud83d\ude00"), new ArrayList<>(entry.getSignatures().keySet()));
  }
}
