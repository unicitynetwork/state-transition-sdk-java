package org.unicitylabs.sdk.api.bft.trustbase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.util.HexConverter;

/** Named pins, bundles and entries of the shared UTB cache vectors (unsigned test keys). */
final class UtbCacheFixtures {

  private final JsonNode v;

  UtbCacheFixtures() {
    try (InputStream in = UtbCacheFixtures.class
        .getResourceAsStream("/vectors/utb-cache/v1/vectors.json")) {
      this.v = new ObjectMapper().readTree(in);
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
    throw new IllegalStateException(section + " " + name);
  }

  ReleasePin pin(String name) {
    JsonNode p = named("pins", name);
    return new ReleasePin(NetworkId.fromId((short) p.get("network").asInt()),
        Long.parseUnsignedLong(p.get("genesisEpoch").asText()),
        HexConverter.decode(p.get("genesisEntryId").asText()),
        Long.parseUnsignedLong(p.get("pinnedEpoch").asText()),
        HexConverter.decode(p.get("pinnedEntryId").asText()),
        HexConverter.decode(p.get("bundle").asText()),
        HexConverter.decode(p.get("bundleSha256").asText()));
  }

  byte[] bundle(String name) {
    return HexConverter.decode(named("bundles", name).get("hex").asText());
  }

  TrustBaseEntry entry(String name) {
    return TrustBaseEntry.decode(HexConverter.decode(named("entries", name).get("hex").asText()));
  }

  static String key(ReleasePin pin, String namespace) {
    return TrustBaseCacheKey.of(pin.getNetworkId(), pin.getGenesisEntryId(), namespace);
  }
}
