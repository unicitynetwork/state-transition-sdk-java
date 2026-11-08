package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NonInclusionVerificationException.Reason;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseBundle;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseBundleLimits;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistory;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * A real seal of each existing network verifies through the V2 bundle that describes its live
 * authority (owner &sect;44/&sect;46). The fixtures are those of sdk-rust (9e92ae2): per network the
 * unicity-ids genesis as a one-entry bundle, and the answer of the network's public gateway (no key)
 * to {@code get_non_inclusion_proof.v1} for a random state id, which carries a unicity certificate
 * signed by the live validators. See {@code vectors/real-seals/README.md}. These prove that existing
 * seals authenticate under the V2 genesis (epoch 1); they do not show a live epoch-2 handoff.
 */
class RealSealsTest {

  private static final TrustBaseBundleLimits LIMITS = new TrustBaseBundleLimits(1 << 20, 16);
  private static final String TESTNET2_ID =
      "4b49eb2bb0729051b89a48668fd6ef1fda284aad2e10b7d5f23aad4b5faef37f";
  private static final String MAINNET_ID =
      "0b6f8143e19e2a585be3534e8f1062f36c3b9621e506158f10abfbc9a56f4e4c";

  private static byte[] read(String name) {
    try (InputStream in = RealSealsTest.class
        .getResourceAsStream("/vectors/real-seals/" + name)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] hexOf(String name) {
    return HexConverter.decode(new String(read(name), StandardCharsets.UTF_8).trim());
  }

  private static TrustBaseHistory historyOf(String network, String entryId) {
    TrustBaseBundle bundle = TrustBaseBundle.decode(read("unicity-trustbase." + network + ".cbor"),
        LIMITS);
    return TrustBaseHistory.fromBundle(bundle, HexConverter.decode(entryId));
  }

  private static StateId stateIdOf(String network) {
    return StateId.fromCbor(CborSerializer.encodeByteString(hexOf(network + ".stateid.hex")));
  }

  private static NonInclusionProof proofOf(String network, byte[] wire) {
    return new NonInclusionProof(stateIdOf(network), wire);
  }

  /** Verify with the shard the gateway answered for pinned, as an application does for a sharded network. */
  private static NonInclusionOutcome verify(NonInclusionProof proof, TrustBaseHistory history) {
    NonInclusionContext context = NonInclusionContext.unsharded()
        .withShard(proof.decode().unicityCertificate.getShardTreeCertificate().getShard());
    return NonInclusionVerifier.verify(history, proof, context);
  }

  @Test
  void theBundlesAreTheReleaseAssets() {
    for (String network : new String[] {"testnet2", "mainnet"}) {
      try (InputStream in = RealSealsTest.class
          .getResourceAsStream("/release-pins/unicity-trustbase." + network + ".cbor")) {
        assertArrayEquals(in.readAllBytes(), read("unicity-trustbase." + network + ".cbor"));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  @Test
  void aRealTestnet2SealVerifiesThroughTheV2Bundle() {
    TrustBaseHistory history = historyOf("testnet2", TESTNET2_ID);
    assertEquals(4, history.getNetworkId().get().getId());
    NonInclusionOutcome outcome = verify(proofOf("testnet2", hexOf("testnet2.non-inclusion.hex")),
        history);
    assertEquals(NonInclusionOutcome.Kind.ABSENT_AT_SNAPSHOT, outcome.getKind());
    assertEquals(4, outcome.getSnapshot().getNetworkId().getId());
    assertEquals(1, outcome.getSnapshot().getRootEpoch());
  }

  @Test
  void aRealMainnetSealVerifiesThroughTheV2Bundle() {
    TrustBaseHistory history = historyOf("mainnet", MAINNET_ID);
    assertEquals(1, history.getNetworkId().get().getId());
    NonInclusionOutcome outcome = verify(proofOf("mainnet", hexOf("mainnet.non-inclusion.hex")),
        history);
    assertEquals(NonInclusionOutcome.Kind.ABSENT_AT_SNAPSHOT, outcome.getKind());
    assertEquals(1, outcome.getSnapshot().getNetworkId().getId());
    assertEquals(1, outcome.getSnapshot().getRootEpoch());
  }

  @Test
  void controlsAnotherNetworksBundleOrDamagedSignaturesDoNotVerify() {
    // testnet2's seal under mainnet's bundle
    NonInclusionProof testnet2 = proofOf("testnet2", hexOf("testnet2.non-inclusion.hex"));
    NonInclusionVerificationException other = assertThrows(NonInclusionVerificationException.class,
        () -> verify(testnet2, historyOf("mainnet", MAINNET_ID)));
    assertEquals(Reason.AUTHORITY_REJECTED, other.getReason());

    // damaged signatures: the proof still decodes, but once fewer than a quorum verify the seal no
    // longer authenticates (genesis threshold floor(2*4/3)+1 = 3 of 4)
    byte[] raw = hexOf("testnet2.non-inclusion.hex");
    List<byte[]> signatures = new ArrayList<>();
    for (UnicitySeal.SignatureEntry entry : testnet2.decode().unicityCertificate.getUnicitySeal()
        .getSignatures()) {
      signatures.add(entry.getSignature().encode());
    }
    assertEquals(4, signatures.size());
    TrustBaseHistory history = historyOf("testnet2", TESTNET2_ID);
    for (int damagedCount = 0; damagedCount <= 4; damagedCount++) {
      byte[] bad = raw.clone();
      for (byte[] signature : signatures.subList(0, damagedCount)) {
        bad[indexOf(bad, signature) + 10] ^= 1;
      }
      NonInclusionProof damaged = proofOf("testnet2", bad);
      if (damagedCount <= 1) {
        assertEquals(NonInclusionOutcome.Kind.ABSENT_AT_SNAPSHOT,
            verify(damaged, history).getKind(), damagedCount + " damaged");
      } else {
        NonInclusionVerificationException e = assertThrows(NonInclusionVerificationException.class,
            () -> verify(damaged, history));
        assertEquals(Reason.AUTHORITY_REJECTED, e.getReason(), damagedCount + " damaged");
      }
    }
    assertTrue(signatures.get(0).length > 10);
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      boolean match = true;
      for (int j = 0; j < needle.length && match; j++) {
        match = haystack[i + j] == needle[j];
      }
      if (match) {
        return i;
      }
    }
    throw new IllegalStateException("signature not found in the proof bytes");
  }
}
