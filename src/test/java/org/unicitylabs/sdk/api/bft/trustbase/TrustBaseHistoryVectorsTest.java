package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.util.HexConverter;

/** The shared bft-go-base history, weighted-quorum and bundle vectors. */
class TrustBaseHistoryVectorsTest {

  private static final JsonNode VECTORS = TrustBaseVectors.shared();
  private static final TrustBaseBundleLimits LIMITS = new TrustBaseBundleLimits(1 << 20, 64);

  private static final Map<String, Class<? extends TrustBaseException>> OUTCOME_ERRORS = Map.of(
      "conflict", TrustBaseConflictException.class,
      "error:chain", TrustBaseChainException.class,
      "error:invalid", TrustBaseInvalidException.class,
      "error:signature", TrustBaseSignatureException.class,
      "gap", TrustBaseGapException.class);

  private static TrustBaseEntry entry(String hex) {
    return TrustBaseEntry.decode(HexConverter.decode(hex));
  }

  private static TrustBaseEntry named(String name) {
    for (JsonNode vector : VECTORS.get("entries")) {
      if (vector.get("name").asText().equals(name)) {
        return entry(vector.get("hex").asText());
      }
    }
    throw new AssertionError(name);
  }

  @Test
  void everyHistoryVectorHasTheSharedOutcomeAndTheSharedFinalChain() {
    int seen = 0;
    for (JsonNode vector : VECTORS.get("history")) {
      String name = vector.get("name").asText();
      JsonNode steps = vector.get("steps");
      // the reference pins nothing; the first entry of every vector is the genesis, so its id is the pin
      TrustBaseHistory history = TrustBaseHistory.create(entry(steps.get(0).get("entry").asText()).entryId());
      for (JsonNode step : steps) {
        TrustBaseEntry candidate = entry(step.get("entry").asText());
        String outcome = step.get("outcome").asText();
        if (outcome.equals("appended")) {
          assertEquals(TrustBaseAppendOutcome.APPENDED, history.append(candidate), name);
        } else if (outcome.equals("noop")) {
          assertEquals(TrustBaseAppendOutcome.ALREADY_HELD, history.append(candidate), name);
        } else {
          assertThrows(OUTCOME_ERRORS.get(outcome), () -> history.append(candidate), name);
        }
      }
      List<String> held = new ArrayList<>();
      for (TrustBaseEntry e : history.getEntries()) {
        held.add(HexConverter.encode(e.entryId()));
      }
      List<String> expected = new ArrayList<>();
      JsonNode finalIds = vector.get("finalEntryIds");
      if (finalIds != null && !finalIds.isNull()) {
        finalIds.forEach(id -> expected.add(id.asText()));
      }
      assertEquals(expected, held, name);
      seen++;
    }
    assertEquals(11, seen);
  }

  @Test
  void everyWeightedQuorumVectorHasTheSharedVerdict() {
    int seen = 0;
    for (JsonNode vector : VECTORS.get("weightedQuorum")) {
      String name = vector.get("name").asText();
      TrustBaseEntry committee = named(vector.get("committee").asText());
      TrustBaseEntry successor = entry(vector.get("entry").asText());
      Map<String, byte[]> signatures = successor.getSignatures();
      List<String> signers = new ArrayList<>(signatures.keySet());
      List<String> expectedSigners = new ArrayList<>();
      vector.get("signers").forEach(s -> expectedSigners.add(s.asText()));
      assertEquals(expectedSigners, signers, name);

      long signed = 0;
      for (Map.Entry<String, byte[]> signature : signatures.entrySet()) {
        signed += committee.verifySignature(successor.sigBytes(), signature.getValue(),
            signature.getKey());
      }
      assertEquals(vector.get("signedWeight").asLong(), signed, name);
      assertEquals(vector.get("threshold").asLong(), committee.getQuorumThreshold(), name);

      if (vector.get("valid").asBoolean()) {
        committee.verifyQuorumSignatures(successor.sigBytes(), signatures);
      } else {
        assertThrows(TrustBaseSignatureException.class,
            () -> committee.verifyQuorumSignatures(successor.sigBytes(), signatures), name);
      }
      seen++;
    }
    assertEquals(4, seen);
  }

  @Test
  void theSharedBundlesDecodeReEncodeAndInstallThroughAppend() {
    int seen = 0;
    for (JsonNode vector : VECTORS.get("bundles")) {
      String name = vector.get("name").asText();
      byte[] bytes = HexConverter.decode(vector.get("hex").asText());
      TrustBaseBundle bundle = TrustBaseBundle.decode(bytes, LIMITS);
      List<String> ids = new ArrayList<>();
      for (TrustBaseEntry e : bundle.getEntries()) {
        ids.add(HexConverter.encode(e.entryId()));
      }
      List<String> expected = new ArrayList<>();
      vector.get("entryIds").forEach(id -> expected.add(id.asText()));
      assertEquals(expected, ids, name);
      assertArrayEquals(bytes, TrustBaseBundle.encode(bundle.getEntries()), name);

      TrustBaseHistory history =
          TrustBaseHistory.fromBundle(bundle, bundle.getEntries().get(0).entryId());
      assertEquals(bundle.getEntries().size(), history.getEntries().size(), name);
      assertArrayEquals(bytes, history.toBundle(), name);
      seen++;
    }
    assertEquals(2, seen);
  }
}
