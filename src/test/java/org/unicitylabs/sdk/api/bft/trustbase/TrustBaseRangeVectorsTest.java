package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/** The shared GetTrustBase response vectors (bft-core 5d9a8eaa, gettrustbase/testdata/v1) against the page decoder. */
class TrustBaseRangeVectorsTest {

  private static final String RESOURCE = "/vectors/gettrustbase/vectors.json";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode vectors() {
    try (InputStream in = TrustBaseRangeVectorsTest.class.getResourceAsStream(RESOURCE)) {
      return MAPPER.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] hex(JsonNode node) {
    return HexConverter.decode(node.asText());
  }

  private static NetworkId network(JsonNode v) {
    return NetworkId.fromId((short) v.get("network").asInt());
  }

  @Test
  void theVendoredFileIsThePinnedOne() throws Exception {
    byte[] bytes;
    try (InputStream in = TrustBaseRangeVectorsTest.class.getResourceAsStream(RESOURCE)) {
      bytes = in.readAllBytes();
    }
    assertEquals("3f9732fa5d5bed202d326f452f732d625040dbfac81857e19574df25bde3734c",
        HexConverter.encode(MessageDigest.getInstance("SHA-256").digest(bytes)));
  }

  @Test
  void everyCaseDecodesOrIsRefusedAsTheVectorsSay() {
    JsonNode v = vectors();
    NetworkId network = network(v);
    int accepted = 0;
    int encoding = 0;
    int response = 0;
    for (JsonNode c : v.get("cases")) {
      String name = c.get("name").asText();
      byte[] bytes = hex(c.get("hex"));
      if (!c.has("error")) {
        TrustBaseRange r = assertDoesNotThrow(
            () -> TrustBaseRange.decode(bytes, TrustBaseRangeLimits.PAGE, network), name);
        accepted++;
        assertEquals(c.get("status").asInt(), r.getStatus().wire(), name);
        assertEquals(c.get("fromEpoch").asLong(), r.getFromEpoch(), name);
        assertEquals(c.get("effectiveEnd").asLong(), r.getEffectiveEnd(), name);
        if (c.has("endEntryId")) {
          assertArrayEquals(hex(c.get("endEntryId")), r.getEndEntryId(), name);
        } else {
          assertNull(r.getEndEntryId(), name);
        }
        List<Long> epochs = new ArrayList<>();
        if (c.has("entryEpochs")) {
          c.get("entryEpochs").forEach(e -> epochs.add(e.asLong()));
        }
        List<Long> got = new ArrayList<>();
        r.getEntries().forEach(e -> got.add(e.getEpoch()));
        assertEquals(epochs, got, name);
        if (c.has("nextEpoch")) {
          assertEquals(c.get("nextEpoch").asLong(), r.getNextEpoch(), name);
        } else {
          assertNull(r.getNextEpoch(), name);
        }
      } else {
        String errorClass = c.get("error").asText();
        // Go labels a junk embedded entry a response failure (its decode error is wrapped);
        // here it is the entry's own encoding error.
        boolean wantEncoding = errorClass.equals("encoding")
            || name.contains("not a version 2 entry");
        TrustBaseException e = assertThrows(TrustBaseException.class,
            () -> TrustBaseRange.decode(bytes, TrustBaseRangeLimits.PAGE, network), name);
        if (wantEncoding) {
          assertTrue(e instanceof TrustBaseEncodingException, name + ": " + e);
          encoding++;
        } else {
          assertTrue(e instanceof TrustBaseResponseException, name + ": " + e);
          response++;
        }
      }
    }
    assertEquals(v.get("cases").size(), accepted + encoding + response);
    assertTrue(accepted > 0 && encoding > 0 && response > 0);
  }

  @Test
  void aResponseForAnotherNetworkIsRefused() {
    JsonNode v = vectors();
    byte[] bytes = hex(v.get("cases").get(0).get("hex"));
    assertThrows(TrustBaseResponseException.class,
        () -> TrustBaseRange.decode(bytes, TrustBaseRangeLimits.PAGE, NetworkId.fromId((short) 6)));
  }

  @Test
  void theBudgetsAreExact() {
    JsonNode v = vectors();
    NetworkId network = network(v);
    byte[] bytes = hex(v.get("cases").get(0).get("hex")); // the complete history: three entries
    assertDoesNotThrow(
        () -> TrustBaseRange.decode(bytes, new TrustBaseRangeLimits(bytes.length, 3), network));
    assertThrows(TrustBaseEncodingException.class, () -> TrustBaseRange.decode(bytes,
        new TrustBaseRangeLimits(bytes.length - 1, 3), network));
    assertThrows(TrustBaseResponseException.class, () -> TrustBaseRange.decode(bytes,
        new TrustBaseRangeLimits(bytes.length, 2), network));
  }

  private static byte[] rawPage(int network, long from, long end, byte[] endId, byte[]... items) {
    return CborSerializer.encodeTag(39015, CborSerializer.encodeArray(
        CborSerializer.encodeUnsignedInteger(1),
        CborSerializer.encodeUnsignedInteger(network),
        CborSerializer.encodeUnsignedInteger(0),
        CborSerializer.encodeUnsignedInteger(from),
        CborSerializer.encodeUnsignedInteger(end),
        CborSerializer.encodeByteString(endId),
        CborSerializer.encodeArray(items),
        CborSerializer.encodeNull()));
  }

  /** The shared maximum-size entry is one page entry well inside the 1 MiB page; an item over the 128 KiB per-entry cap is refused. */
  @Test
  void theLargestEntryIsAValidPageAndOneOverTheCapIsNot() throws IOException {
    JsonNode v;
    try (InputStream in = TrustBaseRangeVectorsTest.class
        .getResourceAsStream("/vectors/trust-base-v2/max-entry/v1/max-entry.json")) {
      v = MAPPER.readTree(in);
    }
    byte[] entry = hex(v.get("entry"));
    byte[] id = hex(v.get("entryId"));
    int networkId = v.get("network").asInt();
    NetworkId network = NetworkId.fromId((short) networkId);

    byte[] page = rawPage(networkId, 2, 2, id, entry);
    assertTrue(page.length > 90_000 && page.length < TrustBaseRangeLimits.PAGE.getMaxBytes());
    TrustBaseRange r = TrustBaseRange.decode(page, TrustBaseRangeLimits.PAGE, network);
    assertEquals(1, r.getEntries().size());
    assertEquals(2, r.getEntries().get(0).getEpoch());
    assertNull(r.getNextEpoch());

    byte[] big = CborSerializer.encodeByteString(new byte[TrustBaseEntry.MAX_ENTRY_BYTES]);
    assertTrue(big.length > TrustBaseEntry.MAX_ENTRY_BYTES);
    TrustBaseResponseException e = assertThrows(TrustBaseResponseException.class,
        () -> TrustBaseRange.decode(rawPage(networkId, 2, 2, id, big),
            TrustBaseRangeLimits.PAGE, network));
    assertTrue(e.getMessage().contains("size bound"));
  }
}
