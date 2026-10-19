package org.unicitylabs.sdk.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Integrity of the vendored uc-consolidation v1 corpus: the exact file set and every SHA-256 match
 * the Java-side source lock, the pinned upstream manifest hash, and the hashes the upstream
 * manifest itself lists. A fixture update is an explicit change of the lock, never an automatic one.
 */
class UcConsolidationCorpusLockTest {

  private static final Path ROOT = Paths.get("src/test/resources/shared");
  private static final Path CORPUS = ROOT.resolve("uc-consolidation/v1");
  private static final Path LOCK = ROOT.resolve("uc-consolidation-v1.lock");

  private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  private static Map<String, String> actualFiles() throws IOException, NoSuchAlgorithmException {
    Map<String, String> files = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(CORPUS)) {
      for (Path file : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
        files.put(CORPUS.relativize(file).toString().replace('\\', '/'), sha256(file));
      }
    }
    return files;
  }

  @Test
  void fileSetAndHashesMatchTheSourceLock() throws Exception {
    Map<String, String> locked = new TreeMap<>();
    String manifestPin = null;
    for (String line : Files.readAllLines(LOCK, StandardCharsets.UTF_8)) {
      if (line.startsWith("manifest-sha256: ")) {
        manifestPin = line.substring("manifest-sha256: ".length()).trim();
      } else if (!line.startsWith("#") && line.matches("^[0-9a-f]{64} {2}\\S.*$")) {
        locked.put(line.substring(66), line.substring(0, 64));
      }
    }
    Map<String, String> actual = actualFiles();
    assertEquals(locked, actual, "vendored file set or contents differ from the lock");
    assertEquals(
        "159e007448df8b35fa18d8cee951ce3960be60a6c157a10e502bc3576a90b749", manifestPin);
    assertEquals(manifestPin, actual.get("manifest.json"));
  }

  @Test
  void everyHashTheUpstreamManifestListsMatches() throws Exception {
    JsonNode manifest = new ObjectMapper().readTree(CORPUS.resolve("manifest.json").toFile());
    Map<String, String> actual = actualFiles();
    Iterator<Map.Entry<String, JsonNode>> listed = manifest.get("sha256").fields();
    int count = 0;
    while (listed.hasNext()) {
      Map.Entry<String, JsonNode> entry = listed.next();
      assertEquals(entry.getValue().asText(), actual.get(entry.getKey()), entry.getKey());
      count++;
    }
    assertTrue(count > 0);
    // everything but the manifest itself is listed upstream
    assertEquals(actual.size() - 1, count);
  }
}
