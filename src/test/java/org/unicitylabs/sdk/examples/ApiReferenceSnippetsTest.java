package org.unicitylabs.sdk.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Every Java snippet of docs/api-reference.md must be a copy of consecutive lines of a compiled
 * example, so the document cannot show code the build does not check.
 */
class ApiReferenceSnippetsTest {

  private static final Path DOC = Path.of("docs/api-reference.md");
  private static final Path EXAMPLES = Path.of("src/test/java/org/unicitylabs/sdk/examples");

  @Test
  void everyDocumentedSnippetIsCompiledExampleCode() throws IOException {
    List<List<String>> sources = new ArrayList<>();
    try (var files = Files.list(EXAMPLES)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java"))
          .collect(Collectors.toList())) {
        sources.add(lines(Files.readAllLines(file)));
      }
    }

    List<List<String>> snippets = snippets(Files.readAllLines(DOC));
    assertFalse(snippets.isEmpty());
    for (List<String> snippet : snippets) {
      assertTrue(sources.stream().anyMatch(source -> contains(source, snippet)),
          "not in the compiled examples:\n" + String.join("\n", snippet));
    }
  }

  private static List<List<String>> snippets(List<String> doc) {
    List<List<String>> result = new ArrayList<>();
    List<String> current = null;
    for (String line : doc) {
      if (current == null && line.trim().equals("```java")) {
        current = new ArrayList<>();
      } else if (current != null && line.trim().equals("```")) {
        result.add(lines(current));
        current = null;
      } else if (current != null) {
        current.add(line);
      }
    }
    return result;
  }

  private static List<String> lines(List<String> raw) {
    return raw.stream().map(String::trim).filter(l -> !l.isEmpty()).collect(Collectors.toList());
  }

  private static boolean contains(List<String> source, List<String> snippet) {
    return Collections.indexOfSubList(source, snippet) >= 0;
  }
}
