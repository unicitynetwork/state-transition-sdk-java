package org.unicitylabs.sdk.api.bft.trustbase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;

/** Loader of the shared trust-base V2 vectors vendored under src/test/resources/vectors. */
final class TrustBaseVectors {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TrustBaseVectors() {
  }

  /** The bft-go-base vectors (entries, decode, bundles, history, weighted quorum). */
  static JsonNode shared() {
    return load("/vectors/trust-base-v2/vectors.json");
  }

  /** The maximum-size entry fixture (256 members, 128-byte node ids, 256 predecessor signatures). */
  static JsonNode maxEntry() {
    return load("/vectors/trust-base-v2/max-entry/v1/max-entry.json");
  }

  private static JsonNode load(String resource) {
    try (InputStream in = TrustBaseVectors.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing test resource " + resource);
      }
      return MAPPER.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
