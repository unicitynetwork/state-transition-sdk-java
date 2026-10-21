package org.unicitylabs.sdk.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Access to the vendored long-poll corpus (aggregator-go testdata/longpoll). */
public final class LongPollCorpus {

  public static final Path ROOT = Paths.get("src/test/resources/shared");
  public static final Path DIR = ROOT.resolve("longpoll");
  public static final Path LOCK = ROOT.resolve("longpoll.lock");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private LongPollCorpus() {
  }

  public static JsonNode json(String name) {
    try {
      return MAPPER.readTree(DIR.resolve(name).toFile());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String text(String name) {
    try {
      return new String(Files.readAllBytes(DIR.resolve(name)), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  /** Every vendored file by relative path, with its hash. */
  public static Map<String, String> actualFiles() throws IOException, NoSuchAlgorithmException {
    Map<String, String> files = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(DIR)) {
      for (Path file : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
        files.put(DIR.relativize(file).toString().replace('\\', '/'), sha256(file));
      }
    }
    return files;
  }
}
