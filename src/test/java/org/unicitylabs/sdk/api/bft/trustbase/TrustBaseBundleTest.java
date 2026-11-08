package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseResponseWire.Fields;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseTestChain.Committee;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/** The complete-bundle decoder and encoder, one refusal at a time. */
class TrustBaseBundleTest {

  private static final TrustBaseBundleLimits LIMITS = new TrustBaseBundleLimits(1 << 20, 64);

  private final Committee c1 = new Committee(4, 1, 1);
  private final Committee c2 = new Committee(4, 1, 2);
  private final TrustBaseEntry g = TrustBaseTestChain.genesis(5, 1, 1, c1);
  private final TrustBaseEntry e2 =
      TrustBaseTestChain.successor(g, c1, c2, 10, TrustBaseTestChain.firstN(c1, 3));

  private Fields fields(long status, Long next, byte[] end, long from, long effectiveEnd,
      int network, TrustBaseEntry... entries) {
    List<byte[]> raw = new ArrayList<>();
    for (TrustBaseEntry entry : entries) {
      raw.add(entry.encode());
    }
    return new Fields(effectiveEnd, end, raw, from, network, next, status);
  }

  private Fields good() {
    return fields(0, null, e2.entryId(), 1, 2, 5, g, e2);
  }

  private static Fields with(Fields f, Long next, long status, byte[] end, long from, long effective,
      int network) {
    return new Fields(effective, end, f.getEntries(), from, network, next, status);
  }

  private static TrustBaseBundleException refused(Fields f) {
    return assertThrows(TrustBaseBundleException.class,
        () -> TrustBaseBundle.decode(TrustBaseResponseWire.encode(f), LIMITS));
  }

  @Test
  void aGoodBundleRoundTripsAndKeepsTheCanonicalBytesOfEachEntry() {
    byte[] bytes = TrustBaseResponseWire.encode(good());
    TrustBaseBundle bundle = TrustBaseBundle.decode(bytes, LIMITS);
    assertEquals(5, bundle.getNetworkId().getId());
    assertEquals(2, bundle.getEntries().size());
    assertArrayEquals(e2.encode(), bundle.getRaw().get(1));
    assertArrayEquals(bytes, TrustBaseBundle.encode(bundle.getEntries()));
    assertArrayEquals(e2.entryId(), bundle.getTip().entryId());
  }

  @Test
  void aOneEntryBundleHoldsAGenesisOnly() {
    byte[] one = TrustBaseBundle.encode(List.of(g));
    assertEquals(1, TrustBaseBundle.decode(one, LIMITS).getEntries().size());
    byte[] partial = TrustBaseResponseWire.encode(fields(0, null, e2.entryId(), 2, 2, 5, e2));
    assertThrows(TrustBaseBundleException.class, () -> TrustBaseBundle.decode(partial, LIMITS));
  }

  @Test
  void aStatusThatIsNotOkOrACursorOrNoEntriesOrNoEndIdIsRefused() {
    Fields f = good();
    assertTrue(refused(with(f, null, 1, e2.entryId(), 1, 2, 5)).getMessage().contains("Status 1"));
    assertTrue(refused(with(f, 3L, 0, e2.entryId(), 1, 2, 5)).getMessage().contains("cursor"));
    assertTrue(refused(new Fields(2, e2.entryId(), List.of(), 1, 5, null, 0)).getMessage()
        .contains("without entries"));
    assertTrue(refused(with(f, null, 0, null, 1, 2, 5)).getMessage().contains("No end entry id"));
  }

  @Test
  void aWrongNetworkRangeOrEndIsRefused() {
    Fields f = good();
    assertTrue(refused(with(f, null, 0, e2.entryId(), 1, 2, 6)).getMessage().contains("network"));
    assertTrue(refused(with(f, null, 0, e2.entryId(), 2, 2, 5)).getMessage().contains("starts at"));
    assertTrue(refused(with(f, null, 0, e2.entryId(), 1, 3, 5)).getMessage().contains("ends at"));
    assertTrue(refused(with(f, null, 0, g.entryId(), 1, 2, 5)).getMessage().contains("end entry id"));
  }

  @Test
  void entriesThatSkipAnEpochOrDoNotLinkAreRefused() {
    Committee c3 = new Committee(4, 1, 3);
    TrustBaseEntry e3 = TrustBaseTestChain.successor(e2, c2, c3, 20, TrustBaseTestChain.firstN(c2, 3));
    assertTrue(refused(fields(0, null, e3.entryId(), 1, 3, 5, g, e3)).getMessage().contains("does not follow"));
    TrustBaseEntry stranger = TrustBaseTestChain.successor(
        TrustBaseTestChain.genesis(5, 1, 2, c2), c2, c3, 20, TrustBaseTestChain.firstN(c2, 3));
    assertTrue(refused(fields(0, null, stranger.entryId(), 1, 2, 5, g, stranger)).getMessage()
        .contains("does not link"));
  }

  @Test
  void theBudgetsAreCheckedBeforeAnythingIsRead() {
    byte[] bytes = TrustBaseResponseWire.encode(good());
    assertThrows(TrustBaseEncodingException.class,
        () -> TrustBaseBundle.decode(bytes, new TrustBaseBundleLimits(bytes.length - 1, 64)));
    TrustBaseEncodingException count = assertThrows(TrustBaseEncodingException.class,
        () -> TrustBaseBundle.decode(bytes, new TrustBaseBundleLimits(1 << 20, 1)));
    assertTrue(count.getMessage().contains("entries"), count.getMessage());
    assertThrows(TrustBaseEncodingException.class,
        () -> TrustBaseBundle.decode(bytes, new TrustBaseBundleLimits(1 << 20, 64, e2.encode().length - 1)));
    TrustBaseBundle.decode(bytes, new TrustBaseBundleLimits(bytes.length, 2, e2.encode().length));
  }

  private static byte[] envelopeWithEntries(byte[] entryArray) {
    return CborSerializer.encodeTag(TrustBaseResponseWire.RESPONSE_TAG,
        CborSerializer.encodeArray(CborSerializer.encodeUnsignedInteger(1),
            CborSerializer.encodeUnsignedInteger(5), CborSerializer.encodeUnsignedInteger(0),
            CborSerializer.encodeUnsignedInteger(1), CborSerializer.encodeUnsignedInteger(1),
            CborSerializer.encodeNull(), entryArray, CborSerializer.encodeNull()));
  }

  @Test
  void moreEntriesThanTheBudgetAreRefusedFromTheArrayHeader() {
    byte[][] hundred = new byte[100][];
    Arrays.fill(hundred, CborSerializer.encodeNull());
    byte[] bytes = envelopeWithEntries(CborSerializer.encodeArray(hundred));
    TrustBaseEncodingException e = assertThrows(TrustBaseEncodingException.class,
        () -> TrustBaseBundle.decode(bytes, LIMITS));
    assertEquals("More than 64 entries.", e.getMessage());
  }

  @Test
  void anEntryCountHeaderOfFourBillionIsRefusedAsAnEncodingError() {
    byte[] hugeArray = {(byte) 0x9a, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
    byte[] bytes = envelopeWithEntries(hugeArray);
    assertThrows(TrustBaseEncodingException.class, () -> TrustBaseBundle.decode(bytes, LIMITS));
  }

  @Test
  void theEnvelopeIsStrict() {
    byte[] good = TrustBaseResponseWire.encode(good());
    byte[] trailing = Arrays.copyOf(good, good.length + 1);
    assertThrows(TrustBaseEncodingException.class, () -> TrustBaseBundle.decode(trailing, LIMITS));
    byte[] wrongTag = CborSerializer.encodeTag(39016L, CborSerializer.encodeArray());
    assertThrows(TrustBaseEncodingException.class, () -> TrustBaseBundle.decode(wrongTag, LIMITS));
    // status 8 is undefined, a network above 65535 is a bad encoding
    byte[] status8 = TrustBaseResponseWire.encode(with(good(), null, 8, e2.entryId(), 1, 2, 5));
    assertThrows(TrustBaseEncodingException.class, () -> TrustBaseBundle.decode(status8, LIMITS));
    byte[] shortEnd = TrustBaseResponseWire.encode(with(good(), null, 0, new byte[31], 1, 2, 5));
    assertThrows(TrustBaseEncodingException.class, () -> TrustBaseBundle.decode(shortEnd, LIMITS));
  }

  @Test
  void encodeRefusesEmptyUnlinkedOrMixedNetworkChains() {
    assertThrows(TrustBaseBundleException.class, () -> TrustBaseBundle.encode(List.of()));
    assertThrows(TrustBaseBundleException.class, () -> TrustBaseBundle.encode(List.of(e2)));
    TrustBaseEntry other = TrustBaseTestChain.successor(TrustBaseTestChain.genesis(5, 1, 2, c2), c2, c1,
        10, TrustBaseTestChain.firstN(c2, 3));
    assertThrows(TrustBaseBundleException.class, () -> TrustBaseBundle.encode(List.of(g, other)));
    assertThrows(TrustBaseBundleException.class, () -> TrustBaseBundle.encode(List.of(g, g)));
  }
}
