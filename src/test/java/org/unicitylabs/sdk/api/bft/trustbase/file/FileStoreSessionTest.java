package org.unicitylabs.sdk.api.bft.trustbase.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.FlushResult;
import org.unicitylabs.sdk.api.bft.trustbase.ReleasePin;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseCacheKey;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseSession;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseSessionOptions;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseStoreException;
import org.unicitylabs.sdk.util.HexConverter;

/** A session over the file store saves and reloads with strict revalidation (shared cache vectors). */
class FileStoreSessionTest {

  @TempDir
  Path tmp;

  private final JsonNode v = vectors();

  private static JsonNode vectors() {
    try (InputStream in = FileStoreSessionTest.class
        .getResourceAsStream("/vectors/utb-cache/v1/vectors.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode named(String section, String name) {
    for (JsonNode n : this.v.get(section)) {
      if (n.get("name").asText().equals(name)) {
        return n;
      }
    }
    throw new IllegalStateException(name);
  }

  private ReleasePin pin(String name) {
    JsonNode p = named("pins", name);
    return new ReleasePin(NetworkId.fromId((short) p.get("network").asInt()),
        Long.parseUnsignedLong(p.get("genesisEpoch").asText()),
        HexConverter.decode(p.get("genesisEntryId").asText()),
        Long.parseUnsignedLong(p.get("pinnedEpoch").asText()),
        HexConverter.decode(p.get("pinnedEntryId").asText()),
        HexConverter.decode(p.get("bundle").asText()),
        HexConverter.decode(p.get("bundleSha256").asText()));
  }

  private byte[] bundle(String name) {
    return HexConverter.decode(named("bundles", name).get("hex").asText());
  }

  private TrustBaseEntry entry(String name) {
    return TrustBaseEntry.decode(HexConverter.decode(named("entries", name).get("hex").asText()));
  }

  private static <T> T get(CompletableFuture<T> f) {
    try {
      return f.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new IllegalStateException(e.getCause());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static TrustBaseSession open(ReleasePin pin, FileTrustBaseStore store) {
    return get(TrustBaseSession.openPrivate("test", pin, store,
        TrustBaseSessionOptions.of(Runnable::run))).getSession();
  }

  @Test
  void aSessionSavesAndASecondStoreInstanceLoadsItWithStrictRevalidation() throws IOException {
    ReleasePin pin = pin("A1-3");
    String key = TrustBaseCacheKey.of(pin.getNetworkId(), pin.getGenesisEntryId(), "test");
    FileTrustBaseStore first = new FileTrustBaseStore(this.tmp, Runnable::run);
    TrustBaseSession s = open(pin, first);
    s.append(entry("A4"));
    s.append(entry("A5"));
    assertTrue(get(s.flush()).isWrote());
    assertArrayEquals(bundle("A1-5"), Files.readAllBytes(first.filePath(key)));

    TrustBaseSession again = open(pin, new FileTrustBaseStore(this.tmp, Runnable::run));
    assertEquals(5, again.history().getTip().get().getEpoch());
    assertFalse(again.isDirty());

    // damage on disk is detected on load: the embedded history is used and the file is replaced
    Files.write(first.filePath(key), new byte[] {1, 2, 3});
    TrustBaseSession.Opened damaged = get(TrustBaseSession.openPrivate("test", pin,
        new FileTrustBaseStore(this.tmp, Runnable::run),
        TrustBaseSessionOptions.of(Runnable::run)));
    assertEquals(1, damaged.getDiagnostics().size());
    assertEquals(3, damaged.getSession().history().getTip().get().getEpoch());
    assertTrue(get(damaged.getSession().flush()).isWrote());
    assertArrayEquals(bundle("A1-3"), Files.readAllBytes(first.filePath(key)));
  }

  @Test
  void aBusyLockIsAPendingFlushThatKeepsTheHistory() throws IOException {
    ReleasePin pin = pin("A1-3");
    String key = TrustBaseCacheKey.of(pin.getNetworkId(), pin.getGenesisEntryId(), "test");
    FileTrustBaseStore store = new FileTrustBaseStore(this.tmp, Runnable::run);
    TrustBaseSession s = open(pin, store);
    s.append(entry("A4"));
    Files.createDirectory(store.lockPath(key));
    FlushResult flush = get(s.flush());
    assertFalse(flush.isSaved());
    assertInstanceOf(TrustBaseStoreException.class, flush.getError());
    assertEquals(TrustBaseStoreException.Reason.BUSY,
        ((TrustBaseStoreException) flush.getError()).getReason());
    assertTrue(s.isDirty());
    assertEquals(4, s.history().getTip().get().getEpoch());
    Files.delete(store.lockPath(key)); // the application's maintenance decision
    assertTrue(get(s.flush()).isWrote());
  }
}
