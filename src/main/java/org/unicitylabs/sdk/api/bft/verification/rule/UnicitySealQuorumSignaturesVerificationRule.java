package org.unicitylabs.sdk.api.bft.verification.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseMember;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.Signature;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.util.UnsignedLongs;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Rule to verify that the UnicitySeal contains valid quorum signatures of one trust-base entry.
 *
 * <p>Sums the weight of the distinct members of the entry whose signature over the seal hash is
 * valid and bound to the member's key (the recovery byte included). A witness that is not a
 * well-formed signature, an unknown signer and an invalid signature contribute no vote and do not
 * veto the seal. The weight is compared with the entry's threshold as unsigned numbers. The rule
 * stops at the threshold: further signatures cannot change the verdict and are not verified. A
 * seal with more than {@link UnicitySeal#MAX_WITNESSES} supplied signatures, counted whoever
 * signed, is refused before the seal is hashed or any signature is looked at.
 */
public class UnicitySealQuorumSignaturesVerificationRule {

  private static final String RULE = "UnicitySealQuorumSignaturesVerificationRule";

  private UnicitySealQuorumSignaturesVerificationRule() {
  }

  /**
   * Verifies unicity seal signatures and checks that the quorum threshold is reached.
   *
   * @param entry the trust-base entry of the seal's epoch: members, weights and threshold
   * @param unicitySeal unicity seal with node signatures
   * @return verification result with per-signature details; FAIL, never an exception, for any
   *     malformed signature material
   */
  public static VerificationResult<VerificationStatus> verify(TrustBaseEntry entry,
                                                              UnicitySeal unicitySeal) {
    List<VerificationResult<?>> results = new ArrayList<>();
    Map<String, byte[]> witnesses = unicitySeal.getWitnesses();
    if (witnesses == null) {
      return new VerificationResult<>(RULE, VerificationStatus.FAIL,
          "Unicity seal has no signatures", results);
    }
    if (witnesses.size() > UnicitySeal.MAX_WITNESSES) {
      return new VerificationResult<>(RULE, VerificationStatus.FAIL,
          String.format("Unicity seal has %d signatures, more than %d.", witnesses.size(),
              UnicitySeal.MAX_WITNESSES), results);
    }

    DataHash hash = new DataHasher(HashAlgorithm.SHA256)
            .update(unicitySeal.toCborWithoutSignatures())
            .digest();
    long threshold = entry.getQuorumThreshold();
    long signed = 0;
    for (Map.Entry<String, byte[]> witness : witnesses.entrySet()) {
      VerificationResult<?> result = verifySignature(entry, witness.getKey(), witness.getValue(),
          hash);
      results.add(result);
      if (result.getStatus() == VerificationStatus.OK) {
        signed += entry.findMember(witness.getKey()).getWeight();
        if (UnsignedLongs.compare(signed, threshold) >= 0) {
          break;
        }
      }
    }

    if (UnsignedLongs.compare(signed, threshold) >= 0) {
      return new VerificationResult<>(RULE, VerificationStatus.OK,
          "Unicity quorum signatures verification threshold reached", results);
    }
    return new VerificationResult<>(RULE, VerificationStatus.FAIL,
        "Unicity quorum treshold was not reached", results);
  }

  private static VerificationResult<?> verifySignature(
          TrustBaseEntry entry,
          String nodeId,
          byte[] witness,
          DataHash hash
  ) {
    String rule = String.format("SignatureVerificationRule[%s]", nodeId);
    Signature signature;
    try {
      signature = Signature.decode(witness);
    } catch (IllegalArgumentException e) {
      return new VerificationResult<>(rule, VerificationStatus.FAIL,
              "Malformed signature encoding");
    }

    TrustBaseMember member = entry.findMember(nodeId);
    if (member == null) {
      return new VerificationResult<>(rule, VerificationStatus.FAIL, "No root node defined");
    }

    boolean valid;
    try {
      // low-s only, like the JS and Rust clients: the high-s twin of a signature recovers the same
      // key and must not count as a second encoding of the same vote
      valid = SigningService.verifyWithPublicKey(hash, signature, member.getSigKey());
    } catch (RuntimeException e) {
      // attacker-controlled scalars can make the curve arithmetic throw; that is an invalid
      // signature, not an error of the caller
      valid = false;
    }
    if (!valid) {
      return new VerificationResult<>(rule, VerificationStatus.FAIL,
              "Signature verification failed");
    }
    return new VerificationResult<>(rule, VerificationStatus.OK);
  }
}
