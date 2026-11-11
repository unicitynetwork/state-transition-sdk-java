package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.util.HexConverter;

/** The release table: approved assets, the generated manifest, and the full chain of each row. */
class ReleasePinsTest {

  /** The approved bundles of unicity-ids PR 11, merged as ef00ff25 (also shipped by the JS and Rust SDKs). */
  private static final String MAINNET_BUNDLE_SHA =
      "9d01b8713cab42dab269e1ad6e244cf11e7f8c4b580682a98402db348b37bd52";
  private static final String TESTNET2_BUNDLE_SHA =
      "5b0a4fb7bd6c2d71068c0e65659aa60733dcc9083e9290fc22c2dd8e8e60f08a";
  private static final String MANIFEST_SHA =
      "56b390ab0be3c77cd9119e983965d803e4081d44a56596b2cfb64ed179a27fc7";
  private static final String MAINNET_GENESIS =
      "0b6f8143e19e2a585be3534e8f1062f36c3b9621e506158f10abfbc9a56f4e4c";
  private static final String TESTNET2_GENESIS =
      "4b49eb2bb0729051b89a48668fd6ef1fda284aad2e10b7d5f23aad4b5faef37f";

  private static byte[] resource(String name) {
    try (InputStream in = ReleasePinsTest.class.getResourceAsStream("/release-pins/" + name)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ReleasePin row(int networkId) {
    for (ReleasePin pin : ReleasePins.ROWS) {
      if (pin.getNetworkId().getId() == networkId) {
        return pin;
      }
    }
    throw new IllegalStateException("no row " + networkId);
  }

  @Test
  void theTableHoldsTheApprovedPublicNetworksAndNothingElse() {
    assertEquals(2, ReleasePins.ROWS.size());
    ReleasePin mainnet = row(1);
    ReleasePin testnet2 = row(4);
    assertEquals(NetworkId.MAINNET, mainnet.getNetworkId());
    assertEquals(NetworkId.TESTNET2, testnet2.getNetworkId());
    assertEquals(MAINNET_BUNDLE_SHA, HexConverter.encode(mainnet.getBundleSha256()));
    assertEquals(TESTNET2_BUNDLE_SHA, HexConverter.encode(testnet2.getBundleSha256()));
    assertEquals(MAINNET_GENESIS, HexConverter.encode(mainnet.getGenesisEntryId()));
    assertEquals(TESTNET2_GENESIS, HexConverter.encode(testnet2.getGenesisEntryId()));
    // currently the genesis is the release tip, epoch 1
    for (ReleasePin pin : ReleasePins.ROWS) {
      assertEquals(1, pin.getGenesisEpoch());
      assertEquals(1, pin.getPinnedEpoch());
      assertArrayEquals(pin.getGenesisEntryId(), pin.getPinnedEntryId());
    }
    // the manifest is the same file the JS and Rust SDKs ship
    assertEquals(MANIFEST_SHA,
        HexConverter.encode(TrustBaseCacheKey.sha256Of(resource("manifest.json"))));
  }

  @Test
  void everyRowVerifiesTheFullChainFromItsGenesis() {
    for (ReleasePin pin : ReleasePins.ROWS) {
      ReleasePin.VerifiedReleasePin verified = pin.verify(ReleasePin.DEFAULT_LIMITS);
      TrustBaseEntry tip = verified.getHistory().getTip().get();
      assertEquals(pin.getPinnedEpoch(), tip.getEpoch());
      assertArrayEquals(pin.getPinnedEntryId(), tip.entryId());
      assertEquals(pin.getNetworkId(), tip.getNetworkId());
      assertArrayEquals(pin.getBundle(), verified.getHistory().toBundle(),
          "the embedded bundle is the canonical export");
    }
  }

  /** The manifest regenerated from the bundles, in canonical form: sorted keys, 2 spaces, newline. */
  private static String generate(JsonNode existing) {
    StringBuilder out = new StringBuilder("{\n  \"networks\": [\n");
    String[][] files = {{"mainnet", "unicity-trustbase.mainnet.cbor"},
        {"testnet2", "unicity-trustbase.testnet2.cbor"}};
    for (int i = 0; i < files.length; i++) {
      ReleasePin pin = ReleasePin.fromBundle(resource(files[i][1]));
      out.append("    {\n")
          .append("      \"bundle\": \"").append(files[i][1]).append("\",\n")
          .append("      \"bundleSha256\": \"").append(HexConverter.encode(pin.getBundleSha256()))
          .append("\",\n")
          .append("      \"genesisEntryId\": \"").append(HexConverter.encode(pin.getGenesisEntryId()))
          .append("\",\n")
          .append("      \"genesisEpoch\": \"")
          .append(Long.toUnsignedString(pin.getGenesisEpoch())).append("\",\n")
          .append("      \"name\": \"").append(files[i][0]).append("\",\n")
          .append("      \"networkId\": ").append(pin.getNetworkId().getId()).append(",\n")
          .append("      \"pinnedEntryId\": \"").append(HexConverter.encode(pin.getPinnedEntryId()))
          .append("\",\n")
          .append("      \"pinnedEpoch\": \"").append(Long.toUnsignedString(pin.getPinnedEpoch()))
          .append("\"\n")
          .append(i == files.length - 1 ? "    }\n" : "    },\n");
    }
    JsonNode source = existing.get("source");
    out.append("  ],\n  \"source\": {\n")
        .append("    \"commit\": \"").append(source.get("commit").asText()).append("\",\n")
        .append("    \"pullRequest\": ").append(source.get("pullRequest").asInt()).append(",\n")
        .append("    \"repository\": \"").append(source.get("repository").asText()).append("\"\n")
        .append("  },\n  \"version\": 1\n}\n");
    return out.toString();
  }

  @Test
  void theManifestIsExactlyWhatTheGeneratorWritesFromTheBundles() throws IOException {
    byte[] current = resource("manifest.json");
    String generated = generate(new ObjectMapper().readTree(current));
    if ("1".equals(System.getenv("UTB_RELEASE_PINS_UPDATE"))) {
      Path file = Paths.get("src/main/resources/release-pins/manifest.json");
      Files.write(file, generated.getBytes(StandardCharsets.UTF_8));
    }
    assertEquals(new String(current, StandardCharsets.UTF_8), generated,
        "regenerate with UTB_RELEASE_PINS_UPDATE=1 ./gradlew test --tests '*ReleasePinsTest'");
    JsonNode manifest = new ObjectMapper().readTree(current);
    assertEquals("ef00ff25972e4db23485cc982e059a7022c2e463", manifest.get("source").get("commit")
        .asText());
    assertEquals(11, manifest.get("source").get("pullRequest").asInt());
    assertEquals("unicitynetwork/unicity-ids", manifest.get("source").get("repository").asText());
  }

  private static TrustBaseSession.Opened open(NetworkId network, List<ReleasePin> table,
      MemoryTrustBaseStore store) throws Exception {
    CompletableFuture<TrustBaseSession.Opened> f = TrustBaseSession.openPublic(network, null,
        store, TrustBaseSessionOptions.of(Runnable::run).withTable(table));
    try {
      return f.get();
    } catch (ExecutionException e) {
      throw (Exception) e.getCause();
    }
  }

  @Test
  void aSessionOpensEachPublicNetworkFromTheBuiltInTable() throws Exception {
    for (int id : new int[] {1, 4}) {
      TrustBaseSession.Opened opened = open(NetworkId.fromId((short) id), ReleasePins.ROWS,
          new MemoryTrustBaseStore());
      assertEquals(1, opened.getSession().history().getTip().get().getEpoch());
      assertTrue(opened.getSession().getKey().startsWith("utb/v1/" + id + "/"));
      assertTrue(opened.getDiagnostics().isEmpty());
    }
    // raw id 2 (the old testnet) has no row: an unnamed numeric id, not the public test network
    assertThrows(UnknownNetworkException.class,
        () -> open(NetworkId.fromId((short) 2), ReleasePins.ROWS, new MemoryTrustBaseStore()));
  }

  @Test
  void aTamperedRowFailsClosedAndIsNeverRescuedByTheCache() throws Exception {
    ReleasePin good = row(4);
    byte[] bundle = good.getBundle();
    bundle[bundle.length / 2] ^= 1;
    ReleasePin tampered = new ReleasePin(good.getNetworkId(), good.getGenesisEpoch(),
        good.getGenesisEntryId(), good.getPinnedEpoch(), good.getPinnedEntryId(), bundle,
        good.getBundleSha256());
    MemoryTrustBaseStore store = new MemoryTrustBaseStore();
    // a perfectly good cache for the genuine pin does not help
    store.put(TrustBaseCacheKey.of(good.getNetworkId(), good.getGenesisEntryId(), "public"),
        good.getBundle());
    assertThrows(ReleasePinInvalidException.class,
        () -> open(good.getNetworkId(), List.of(tampered), store));
    ReleasePin wrongDigest = new ReleasePin(good.getNetworkId(), good.getGenesisEpoch(),
        good.getGenesisEntryId(), good.getPinnedEpoch(), good.getPinnedEntryId(), good.getBundle(),
        row(1).getBundleSha256());
    assertThrows(ReleasePinInvalidException.class,
        () -> open(good.getNetworkId(), List.of(wrongDigest), store));
  }
}
