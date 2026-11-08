package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.util.HexConverter;

/** The shared bft-go-base vectors: entries (ids, sigBytes) and the decode verdicts. */
class TrustBaseEntryVectorsTest {

  private static final JsonNode VECTORS = TrustBaseVectors.shared();

  @Test
  void everySharedEntryDecodesAndReEncodesToTheSameBytesWithTheSharedIds() {
    int seen = 0;
    for (JsonNode vector : VECTORS.get("entries")) {
      byte[] bytes = HexConverter.decode(vector.get("hex").asText());
      TrustBaseEntry entry = TrustBaseEntry.decode(bytes);
      String name = vector.get("name").asText();
      assertArrayEquals(bytes, entry.encode(), name);
      assertEquals(vector.get("sigBytes").asText(), HexConverter.encode(entry.sigBytes()), name);
      assertEquals(vector.get("entryId").asText(), HexConverter.encode(entry.entryId()), name);
      seen++;
    }
    assertEquals(5, seen);
  }

  @Test
  void theSignatureSetDoesNotChangeTheEntryId() {
    TrustBaseEntry abc = entry("epoch2-signed-abc");
    TrustBaseEntry abd = entry("epoch2-signed-abd");
    assertTrue(!java.util.Arrays.equals(abc.encode(), abd.encode()));
    assertArrayEquals(abc.entryId(), abd.entryId());
    assertArrayEquals(abc.sigBytes(), abd.sigBytes());
  }

  @Test
  void everyDecodeVectorHasTheSharedVerdictAndErrorClass() {
    List<String> checked = new ArrayList<>();
    for (JsonNode vector : VECTORS.get("decode")) {
      String name = vector.get("name").asText();
      byte[] bytes = HexConverter.decode(vector.get("hex").asText());
      if (vector.get("valid").asBoolean()) {
        TrustBaseEntry.decode(bytes);
      } else {
        String errorClass = vector.get("errorClass").asText();
        Class<? extends TrustBaseException> expected = errorClass.equals("encoding")
            ? TrustBaseEncodingException.class : TrustBaseInvalidException.class;
        assertThrows(expected, () -> TrustBaseEntry.decode(bytes), name);
      }
      checked.add(name);
    }
    assertEquals(15, checked.size());
  }

  @Test
  void aSharedSuccessorVerifiesAgainstItsPredecessorAndNotAgainstAWrongOne() {
    TrustBaseEntry genesis = entry("genesis");
    TrustBaseEntry epoch2 = entry("epoch2-signed-abc");
    epoch2.verifySuccessor(genesis);
    entry("epoch2-signed-abd").verifySuccessor(genesis);
    assertThrows(TrustBaseChainException.class, () -> genesis.verifySuccessor(genesis));
    assertThrows(TrustBaseChainException.class, () -> epoch2.verifySuccessor(epoch2));
    // epoch 3 is not a successor of the genesis
    assertThrows(TrustBaseChainException.class,
        () -> entry("epoch3-signed-abc").verifySuccessor(genesis));
    entry("epoch3-signed-abc").verifySuccessor(epoch2);
  }

  private static TrustBaseEntry entry(String name) {
    for (JsonNode vector : VECTORS.get("entries")) {
      if (vector.get("name").asText().equals(name)) {
        return TrustBaseEntry.decode(HexConverter.decode(vector.get("hex").asText()));
      }
    }
    throw new AssertionError(name);
  }
}
