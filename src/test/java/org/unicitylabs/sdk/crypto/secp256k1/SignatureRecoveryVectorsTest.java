package org.unicitylabs.sdk.crypto.secp256k1;

import org.unicitylabs.sdk.api.bft.TrustBaseTestUtils;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealQuorumSignaturesVerificationRule;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * The shared O3 signature-recovery corpus (see PROVENANCE.md next to the vectors), the same file the
 * Rust and JS SDKs consume.
 */
class SignatureRecoveryVectorsTest {

  private static JsonNode fixture;

  @BeforeAll
  static void load() throws IOException {
    try (InputStream in = SignatureRecoveryVectorsTest.class.getResourceAsStream(
        "/org/unicitylabs/sdk/crypto/signature-recovery/v1/vectors.json")) {
      fixture = new ObjectMapper().readTree(in);
    }
  }

  private static boolean accepts(byte[] digest, byte[] encoded, byte[] publicKey) {
    try {
      return SigningService.verifyWithPublicKey(new DataHash(HashAlgorithm.SHA256, digest),
          Signature.decode(encoded), publicKey);
    } catch (IllegalArgumentException e) {
      // not a 65-byte signature with v in 0..3: rejected on decoding
      return false;
    }
  }

  @TestFactory
  List<DynamicTest> primitiveVectors() {
    JsonNode primitive = fixture.get("primitive");
    byte[] digest = HexConverter.decode(primitive.get("messageHashSha256").asText());
    byte[] publicKey = HexConverter.decode(primitive.get("publicKeyCompressed").asText());
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode vector : primitive.get("vectors")) {
      tests.add(DynamicTest.dynamicTest("primitive " + vector.get("name").asText(),
          () -> assertEquals(vector.get("accept").asBoolean(),
              accepts(digest, HexConverter.decode(vector.get("signature").asText()), publicKey))));
    }
    return tests;
  }

  @TestFactory
  List<DynamicTest> recoveryVectors() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode vector : fixture.get("recovery")) {
      tests.add(DynamicTest.dynamicTest("recovery " + vector.get("name").asText(),
          () -> assertEquals(vector.get("accept").asBoolean(),
              accepts(HexConverter.decode(vector.get("digest").asText()),
                  HexConverter.decode(vector.get("signature").asText()),
                  HexConverter.decode(vector.get("publicKeyCompressed").asText())))));
    }
    return tests;
  }

  @TestFactory
  List<DynamicTest> sealCases() {
    JsonNode seal = fixture.get("seal");
    List<org.unicitylabs.sdk.api.bft.trustbase.TrustBaseMember> members = new ArrayList<>();
    for (JsonNode node : seal.get("nodes")) {
      members.add(new org.unicitylabs.sdk.api.bft.trustbase.TrustBaseMember(
          node.get("id").asText(),
          HexConverter.decode(node.get("publicKeyCompressed").asText()),
          node.get("stake").asLong()));
    }
    org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistory trustBase =
        TrustBaseTestUtils.genesis(
            org.unicitylabs.sdk.api.NetworkId.fromId((short) seal.get("networkId").asInt()),
            seal.get("epoch").asLong(), 1, members);
    assertEquals(seal.get("quorumThreshold").asLong(),
        trustBase.getTip().get().getQuorumThreshold(),
        "the fixture quorum is the entry threshold");

    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode testCase : seal.get("cases")) {
      tests.add(DynamicTest.dynamicTest("seal " + testCase.get("name").asText(), () -> {
        UnicitySeal unicitySeal = UnicitySeal.fromCbor(
            HexConverter.decode(testCase.get("sealCbor").asText()));
        assertEquals(testCase.get("accept").asBoolean(),
            UnicitySealQuorumSignaturesVerificationRule.verify(trustBase.getTip().get(), unicitySeal).getStatus()
                == VerificationStatus.OK);
      }));
    }
    return tests;
  }
}
