package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.bft.verification.SealVerificationSession;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateFailure;
import org.unicitylabs.sdk.crypto.secp256k1.Signature;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer.CborMap;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

class SealVerificationLowSTest {

  private static final BigInteger N = new BigInteger(
      "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16);

  /** The high-s twin: s' = n - s with the opposite recovery parity recovers the same key. */
  private static byte[] twin(byte[] witness) {
    byte[] result = Arrays.copyOf(witness, witness.length);
    BigInteger s = new BigInteger(1, Arrays.copyOfRange(witness, 32, 64));
    byte[] flipped = N.subtract(s).toByteArray();
    Arrays.fill(result, 32, 64, (byte) 0);
    System.arraycopy(flipped, Math.max(0, flipped.length - 32), result, 64 - Math.min(32, flipped.length),
        Math.min(32, flipped.length));
    result[64] = (byte) (witness[64] ^ 1);
    return result;
  }

  private static UnicitySeal withWitnesses(UnicitySeal seal, Map<String, byte[]> witnesses) {
    java.util.Set<CborMap.Entry> entries = new java.util.LinkedHashSet<>();
    for (Map.Entry<String, byte[]> witness : witnesses.entrySet()) {
      entries.add(new CborMap.Entry(CborSerializer.encodeTextString(witness.getKey()),
          CborSerializer.encodeByteString(witness.getValue())));
    }
    byte[] unsigned = seal.toCborWithoutSignatures();
    byte[] map = CborSerializer.encodeMap(new CborMap(entries));
    byte[] bytes = new byte[unsigned.length - 1 + map.length];
    System.arraycopy(unsigned, 0, bytes, 0, unsigned.length - 1);
    System.arraycopy(map, 0, bytes, unsigned.length - 1, map.length);
    return UnicitySeal.fromCbor(bytes);
  }

  @Test
  void aHighSTwinOfAValidSignatureCountsNoVote() {
    TestChain.Authority authority = new TestChain.Authority();
    UnicitySeal seal = authority.seal(5, new byte[32], 0, 1, 2);
    Map<String, byte[]> witnesses = new LinkedHashMap<>(seal.getWitnesses());
    String signer = witnesses.keySet().iterator().next();
    byte[] original = witnesses.get(signer);
    Signature.decode(original);
    byte[] high = twin(original);
    // the twin is a well-formed signature that would recover the same key
    Signature.decode(high);
    witnesses.put(signer, high);
    UnicitySeal twinned = withWitnesses(seal, witnesses);

    SealVerificationSession session = SealVerificationSession.create(authority.trustBase());
    assertEquals(VerificationStatus.OK, session.authenticate(seal).getStatus());
    assertEquals(UnicityCertificateFailure.QUORUM_NOT_REACHED,
        session.authenticate(twinned).getFailure());
    // the legacy path applies the same rule
    assertEquals(VerificationStatus.FAIL, session.verifyLegacyQuorum(twinned).getStatus());
  }
}
