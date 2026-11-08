package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.util.HexConverter;

/** The shared UTB cache vectors (sdk-rust c18f8da, copied by JS) against the session. */
class TrustBaseSessionVectorsTest {

  private static final String RESOURCE = "/vectors/utb-cache/v1/vectors.json";
  private static final String NAMESPACE = "test";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final byte[] raw = load();
  private final JsonNode v = parse(raw);

  private static byte[] load() {
    try (InputStream in = TrustBaseSessionVectorsTest.class.getResourceAsStream(RESOURCE)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode parse(byte[] bytes) {
    try {
      return MAPPER.readTree(bytes);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] hex(JsonNode node) {
    return HexConverter.decode(node.asText());
  }

  private JsonNode named(String section, String name) {
    for (JsonNode n : this.v.get(section)) {
      if (n.get("name").asText().equals(name)) {
        return n;
      }
    }
    throw new IllegalStateException(section + " " + name);
  }

  private ReleasePin pin(String name) {
    return pinOf(named("pins", name));
  }

  private static ReleasePin pinOf(JsonNode p) {
    return new ReleasePin(NetworkId.fromId((short) p.get("network").asInt()),
        Long.parseUnsignedLong(p.get("genesisEpoch").asText()), hex(p.get("genesisEntryId")),
        Long.parseUnsignedLong(p.get("pinnedEpoch").asText()), hex(p.get("pinnedEntryId")),
        hex(p.get("bundle")), hex(p.get("bundleSha256")));
  }

  private byte[] bundle(String name) {
    return hex(named("bundles", name).get("hex"));
  }

  private TrustBaseEntry entry(String name) {
    return TrustBaseEntry.decode(hex(named("entries", name).get("hex")));
  }

  private byte[] cacheBytes(JsonNode c) {
    if (c == null || c.isNull()) {
      return null;
    }
    if (c.has("bundle")) {
      return bundle(c.get("bundle").asText());
    }
    return hex(c.get("hex"));
  }

  private static TrustBaseSessionOptions optionsOf(JsonNode c) {
    JsonNode limits = c.get("limits");
    int maxBytes = 16 * 1024 * 1024;
    int maxEntries = 4096;
    int maxEntryBytes = TrustBaseEntry.MAX_ENTRY_BYTES;
    if (limits != null && !limits.isNull()) {
      if (!limits.path("maxEntries").isMissingNode() && !limits.get("maxEntries").isNull()) {
        maxEntries = limits.get("maxEntries").asInt();
      }
      if (!limits.path("maxEntryBytes").isMissingNode() && !limits.get("maxEntryBytes").isNull()) {
        maxEntryBytes = limits.get("maxEntryBytes").asInt();
      }
      if (!limits.path("maxBundleBytes").isMissingNode()
          && !limits.get("maxBundleBytes").isNull()) {
        maxBytes = limits.get("maxBundleBytes").asInt();
      }
    }
    return TrustBaseSessionOptions.of(Runnable::run)
        .withLimits(new TrustBaseBundleLimits(maxBytes, maxEntries, maxEntryBytes));
  }

  /** A store that fails the read, or serves bytes without enforcing the byte budget. */
  private static final class FaultStore implements TrustBaseStore {

    private final MemoryTrustBaseStore inner;
    private final String fault;

    FaultStore(MemoryTrustBaseStore inner, String fault) {
      this.inner = inner;
      this.fault = fault;
    }

    @Override
    public CompletableFuture<Optional<Stored>> read(String key, int maxBytes) {
      switch (this.fault) {
        case "unreadable":
          return CompletableFuture.failedFuture(new TrustBaseStoreException(
              TrustBaseStoreException.Reason.IO, "injected"));
        case "oversize":
          return CompletableFuture.failedFuture(new TrustBaseStoreException(
              TrustBaseStoreException.Reason.OVERSIZE, "injected"));
        default: // ignoresBudget
          return this.inner.read(key, Integer.MAX_VALUE);
      }
    }

    @Override
    public CompletableFuture<CasOutcome> compareAndSwap(String key, String expected,
        byte[] bytes) {
      return this.inner.compareAndSwap(key, expected, bytes);
    }
  }

  private static TrustBaseStore storeFor(JsonNode c, MemoryTrustBaseStore memory) {
    JsonNode kind = c.get("store");
    return kind == null || kind.isNull() ? memory : new FaultStore(memory, kind.asText());
  }

  private static <T> T get(CompletableFuture<T> future) {
    try {
      return future.get();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new IllegalStateException(e.getCause());
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  private static TrustBaseSession.Opened open(ReleasePin pin, TrustBaseStore store,
      TrustBaseSessionOptions options) {
    return get(TrustBaseSession.openPrivate(NAMESPACE, pin, store, options));
  }

  private static String keyOf(ReleasePin pin) {
    return TrustBaseCacheKey.of(pin.getNetworkId(), pin.getGenesisEntryId(), NAMESPACE);
  }

  private static String diagName(CacheDiagnostic d) {
    if (!d.isRejection()) {
      return "CacheUnavailable";
    }
    switch (d.getRejection()) {
      case MALFORMED:
        return "CacheRejected:malformed";
      case WRONG_NETWORK:
        return "CacheRejected:wrongNetwork";
      case WRONG_GENESIS:
        return "CacheRejected:wrongGenesis";
      case PIN_MISMATCH:
        return "CacheRejected:pinMismatch";
      default:
        return "CacheRejected:oversize";
    }
  }

  private static List<String> names(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(x -> out.add(x.asText()));
    return out;
  }

  private static String flushName(FlushResult f) {
    if (f.isSaved()) {
      return f.isWrote() ? "saved:wrote" : "saved:noop";
    }
    Throwable e = f.getError();
    if (e instanceof CacheRejectedException) {
      return ((CacheRejectedException) e).getReason() == CacheRejection.PIN_MISMATCH
          ? "pending:PinMismatch" : "pending:Oversize";
    }
    if (e instanceof HistoryConflictedException) {
      return "pending:HistoryConflicted";
    }
    return "pending:other:" + e;
  }

  private static String refusalClass(TrustBaseException e) {
    if (e instanceof TrustBaseNetworkException) {
      return "refused:network";
    }
    if (e instanceof TrustBaseBeforeGenesisException) {
      return "refused:beforeGenesis";
    }
    if (e instanceof TrustBaseSignatureException) {
      return "refused:signature";
    }
    if (e instanceof TrustBaseChainException) {
      return "refused:chain";
    }
    if (e instanceof TrustBaseInvalidException) {
      return "refused:invalid";
    }
    if (e instanceof TrustBaseGapException) {
      return "refused:gap";
    }
    if (e instanceof TrustBaseEncodingException) {
      return "refused:encoding";
    }
    return "refused:other";
  }

  @Test
  void theVendoredFileIsThePinnedOne() {
    assertEquals("a071fef1f3d5c34a2918e8d9f6e50a083aa87bc1261740f5e4ecedb7b8790342",
        HexConverter.encode(TrustBaseCacheKey.sha256(this.raw)));
  }

  @Test
  void bundlesReencodeByteForByte() {
    int checked = 0;
    for (JsonNode b : this.v.get("bundles")) {
      if (b.get("entryIds").size() == 0) {
        continue;
      }
      byte[] bytes = hex(b.get("hex"));
      TrustBaseHistory history;
      try {
        history = TrustBaseHistory.fromBundle(TrustBaseBundle.decode(bytes,
            ReleasePin.DEFAULT_LIMITS), TrustBaseBundle.decode(bytes,
            ReleasePin.DEFAULT_LIMITS).getEntries().get(0).entryId());
      } catch (TrustBaseException e) {
        continue; // the invalid ones are exercised through the pins and the cache cases
      }
      assertArrayEquals(bytes, history.toBundle(), b.get("name").asText());
      List<String> ids = new ArrayList<>();
      history.getEntries().forEach(e -> ids.add(HexConverter.encode(e.entryId())));
      assertEquals(names(b.get("entryIds")), ids, b.get("name").asText());
      checked++;
    }
    assertTrue(checked > 5);
  }

  @Test
  void pinsVerifyOrAreReleasePinInvalid() {
    for (JsonNode p : this.v.get("pins")) {
      String name = p.get("name").asText();
      ReleasePin pin = pinOf(p);
      if (p.get("valid").asBoolean()) {
        pin.verify(ReleasePin.DEFAULT_LIMITS);
      } else {
        assertEquals("ReleasePinInvalid", p.get("error").asText());
        assertThrows(ReleasePinInvalidException.class,
            () -> pin.verify(ReleasePin.DEFAULT_LIMITS), name);
        assertThrows(ReleasePinInvalidException.class, () -> open(pin, new MemoryTrustBaseStore(),
            TrustBaseSessionOptions.of(Runnable::run)), "open " + name);
      }
    }
  }

  @Test
  void keysAndRevisions() {
    for (JsonNode k : this.v.get("key")) {
      assertEquals(k.get("key").asText(), TrustBaseCacheKey.of(
          NetworkId.fromId((short) k.get("network").asInt()), hex(k.get("genesisEntryId")),
          k.get("namespace").asText()));
    }
    for (JsonNode r : this.v.get("revision")) {
      assertEquals(r.get("revision").asText(), TrustBaseCacheKey.revision(hex(r.get("hex"))));
    }
  }

  @Test
  void openCases() {
    for (JsonNode c : this.v.get("open")) {
      String name = c.get("name").asText();
      ReleasePin pin = pin(c.get("pin").asText());
      MemoryTrustBaseStore memory = new MemoryTrustBaseStore();
      String key = keyOf(pin);
      byte[] cache = cacheBytes(c.get("cache"));
      if (cache != null) {
        memory.put(key, cache);
      }
      byte[] before = memory.get(key);
      TrustBaseSession.Opened opened = open(pin, storeFor(c, memory), optionsOf(c));
      TrustBaseSession session = opened.getSession();
      JsonNode e = c.get("expect");
      TrustBaseEntry tip = session.history().getTip().get();
      assertEquals(e.get("tipEpoch").asText(), Long.toUnsignedString(tip.getEpoch()), name);
      assertEquals(e.get("tipEntryId").asText(), HexConverter.encode(tip.entryId()), name);
      List<String> got = new ArrayList<>();
      opened.getDiagnostics().forEach(d -> got.add(diagName(d)));
      assertEquals(names(e.get("diagnostics")), got, name);
      assertEquals(e.get("flushNeeded").asBoolean(), session.isDirty(), name);
      if (e.has("exportBundle")) {
        assertArrayEquals(bundle(e.get("exportBundle").asText()), session.exportBundle(),
            name + ": exported bytes");
      }
      assertArrayEquals(before, memory.get(key), name + ": open writes nothing");
    }
  }

  @Test
  void flushCases() {
    for (JsonNode c : this.v.get("flush")) {
      String name = c.get("name").asText();
      ReleasePin pin = pin(c.get("pin").asText());
      MemoryTrustBaseStore memory = new MemoryTrustBaseStore();
      String key = keyOf(pin);
      byte[] cache = cacheBytes(c.get("cache"));
      if (cache != null) {
        memory.put(key, cache);
      }
      TrustBaseSession session = open(pin, storeFor(c, memory), optionsOf(c)).getSession();
      byte[] after = cacheBytes(c.get("storeAfterOpen"));
      if (after != null) {
        memory.put(key, after);
      }
      for (JsonNode o : c.get("offers")) {
        try {
          session.append(entry(o.asText()));
        } catch (TrustBaseException ignored) {
          // the refusals are the offer cases' business
        }
      }
      byte[] before = memory.get(key);
      FlushResult flush = get(session.flush());
      List<String> diags = new ArrayList<>();
      flush.getDiagnostics().forEach(d -> diags.add(diagName(d)));
      JsonNode e = c.get("expect");
      assertEquals(e.get("result").asText(), flushName(flush), name);
      assertEquals(names(e.get("diagnostics")), diags, name + ": flush diagnostics");
      if (e.has("tipEpoch")) {
        TrustBaseEntry tip = TrustBaseBundle.decode(session.exportBundle(),
            ReleasePin.DEFAULT_LIMITS).getTip();
        assertEquals(e.get("tipEpoch").asText(), Long.toUnsignedString(tip.getEpoch()), name);
      }
      byte[] stored = memory.get(key);
      switch (e.get("stored").asText()) {
        case "unchanged":
          assertArrayEquals(before, stored, name);
          break;
        case "absent":
          assertEquals(null, stored, name);
          break;
        default:
          assertArrayEquals(bundle(e.get("stored").asText()), stored, name);
      }
    }
  }

  @Test
  void offerCases() {
    for (JsonNode c : this.v.get("offer")) {
      String name = c.get("name").asText();
      ReleasePin pin = pin(c.get("pin").asText());
      MemoryTrustBaseStore memory = new MemoryTrustBaseStore();
      byte[] cache = cacheBytes(c.get("cache"));
      if (cache != null) {
        memory.put(keyOf(pin), cache);
      }
      TrustBaseSession session = open(pin, memory,
          TrustBaseSessionOptions.of(Runnable::run)).getSession();
      List<String> got = new ArrayList<>();
      for (JsonNode o : c.get("offers")) {
        if (o.asText().equals("@verify")) {
          try {
            session.history().getTip();
            got.add("ok");
          } catch (HistoryConflictedException ex) {
            got.add("HistoryConflicted");
          }
          continue;
        }
        try {
          got.add(session.append(entry(o.asText())) == TrustBaseAppendOutcome.APPENDED
              ? "appended" : "noop");
        } catch (HistoryConflictedException ex) {
          got.add("HistoryConflicted");
        } catch (TrustBaseConflictException ex) {
          assertTrue(ex.isAuthenticated(), name);
          assertFalse(Arrays.equals(ex.getDetails().getHeldEntryId(),
              ex.getDetails().getOfferedEntryId()), name);
          got.add("Conflict");
        } catch (TrustBasePinMismatchException ex) {
          got.add("PinMismatch");
        } catch (TrustBaseException ex) {
          got.add(refusalClass(ex));
        }
      }
      List<String> want = new ArrayList<>();
      c.get("expect").get("results").forEach(
          x -> want.add(x.asText().replace("noop-checked", "refused:signature")));
      assertEquals(want, got, name);
      assertEquals(c.get("expect").get("conflicted").asBoolean(), session.getConflict() != null,
          name);
      if (session.getConflict() != null) {
        assertNotNull(session.getConflict().getDetails().getHeldEntry(), name);
        assertNotNull(session.getConflict().getDetails().getOfferedEntry(), name);
      }
      TrustBaseEntry tip = TrustBaseBundle.decode(session.exportBundle(),
          ReleasePin.DEFAULT_LIMITS).getTip();
      assertEquals(c.get("expect").get("tipEpoch").asText(), Long.toUnsignedString(tip.getEpoch()),
          name);
    }
  }
}
