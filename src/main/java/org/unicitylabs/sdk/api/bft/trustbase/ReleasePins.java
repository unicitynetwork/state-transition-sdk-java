package org.unicitylabs.sdk.api.bft.trustbase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * The release table of this SDK build, one row per public network: the approved genesis bundles of
 * unicity-ids (PR 11, source commit recorded in {@code release-pins/manifest.json}) with their
 * digests, EntryIDs and epochs. The assets are resources of the library, byte-identical to the
 * ones the JavaScript and Rust SDKs ship (the manifest has the same sha256). Nothing is fetched and
 * nothing is trusted from beside a download: the table comes with the authenticated release.
 *
 * <p>The rows are not verified here; a session verifies the row it uses (digest, the full chain
 * from the pinned genesis, both identities) when it opens, and a failure is fatal
 * ({@link ReleasePinInvalidException}). The manifest is regenerated from the bundles by
 * {@code ReleasePinsTest} (set {@code UTB_RELEASE_PINS_UPDATE=1}), which fails on any difference.
 * A future pin must extend the previous checkpoint.
 */
public final class ReleasePins {

  private static final String DIRECTORY = "/release-pins/";

  /** The rows of the built-in table. */
  public static final List<ReleasePin> ROWS = load();

  private ReleasePins() {
  }

  private static List<ReleasePin> load() {
    JsonNode manifest = parse(read("manifest.json"));
    if (manifest.path("version").asInt() != 1) {
      throw new IllegalStateException("Unsupported release pin manifest version.");
    }
    List<ReleasePin> rows = new ArrayList<>();
    for (JsonNode row : manifest.get("networks")) {
      rows.add(new ReleasePin(NetworkId.fromId((short) row.get("networkId").asInt()),
          Long.parseUnsignedLong(row.get("genesisEpoch").asText()),
          HexConverter.decode(row.get("genesisEntryId").asText()),
          Long.parseUnsignedLong(row.get("pinnedEpoch").asText()),
          HexConverter.decode(row.get("pinnedEntryId").asText()),
          read(row.get("bundle").asText()), HexConverter.decode(row.get("bundleSha256").asText())));
    }
    return Collections.unmodifiableList(rows);
  }

  private static JsonNode parse(byte[] bytes) {
    try {
      return new ObjectMapper().readTree(bytes);
    } catch (IOException e) {
      throw new IllegalStateException("The release pin manifest is not JSON.", e);
    }
  }

  private static byte[] read(String name) {
    try (InputStream in = ReleasePins.class.getResourceAsStream(DIRECTORY + name)) {
      if (in == null) {
        throw new IllegalStateException("Missing release asset " + name + ".");
      }
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read release asset " + name + ".", e);
    }
  }
}
