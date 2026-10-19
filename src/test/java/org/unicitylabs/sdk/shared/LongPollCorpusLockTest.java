package org.unicitylabs.sdk.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Integrity of the vendored long-poll corpus: the exact file set and every SHA-256 match the
 * Java-side source lock, and the checksum table of the upstream README matches the files it
 * names. A fixture update is an explicit change of the lock, never an automatic one.
 */
class LongPollCorpusLockTest {

  @Test
  void fileSetAndHashesMatchTheSourceLock() throws Exception {
    Map<String, String> locked = new TreeMap<>();
    String readmePin = null;
    String commit = null;
    for (String line : Files.readAllLines(LongPollCorpus.LOCK, StandardCharsets.UTF_8)) {
      if (line.startsWith("readme-sha256: ")) {
        readmePin = line.substring("readme-sha256: ".length()).trim();
      } else if (line.startsWith("source-commit: ")) {
        commit = line.substring("source-commit: ".length()).trim();
      } else if (!line.startsWith("#") && line.matches("^[0-9a-f]{64} {2}\\S.*$")) {
        locked.put(line.substring(66), line.substring(0, 64));
      }
    }
    Map<String, String> actual = LongPollCorpus.actualFiles();
    assertEquals(locked, actual, "vendored file set or contents differ from the lock");
    assertEquals("51f2ca8b4ad58d55e11be4c281051cae1e72d773", commit);
    assertEquals(readmePin, actual.get("README.md"));
  }

  @Test
  void theUpstreamReadmeChecksumTableMatchesTheFiles() throws Exception {
    Map<String, String> actual = LongPollCorpus.actualFiles();
    Pattern row = Pattern.compile("^\\| `([^`]+)` \\| ([0-9a-f]{64}) \\|$", Pattern.MULTILINE);
    Matcher matcher = row.matcher(LongPollCorpus.text("README.md"));
    int count = 0;
    while (matcher.find()) {
      assertEquals(matcher.group(2), actual.get(matcher.group(1)), matcher.group(1));
      count++;
    }
    assertEquals(5, count, "the README lists five fixture files");
    // everything but the README itself is listed
    assertEquals(actual.size() - 1, count);
  }
}
