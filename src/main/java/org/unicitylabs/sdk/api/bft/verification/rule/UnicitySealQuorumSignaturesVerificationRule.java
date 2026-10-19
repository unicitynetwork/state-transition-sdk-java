package org.unicitylabs.sdk.api.bft.verification.rule;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.RootTrustBase.NodeInfo;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.Signature;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Rule to verify that the UnicitySeal contains valid quorum signatures.
 *
 * <p>Counts distinct signers of the trust base whose signature over the seal hash is valid and
 * bound to the signer's key (the recovery byte included). A witness that is not a well-formed
 * signature, an unknown signer and an invalid signature contribute no vote and do not veto the
 * seal. The count is compared with the threshold as unsigned numbers. The rule stops at the
 * threshold: further signatures cannot change the verdict and are not verified.
 */
public class UnicitySealQuorumSignaturesVerificationRule {

  /** Half of the order of the secp256k1 group: the largest accepted s. */
  private static final BigInteger HALF_ORDER = new BigInteger(
          "7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0", 16);

  private UnicitySealQuorumSignaturesVerificationRule() {
  }

  /**
   * Verifies unicity seal signatures and checks that the quorum threshold is reached.
   *
   * @param trustBase trust base containing root nodes and quorum threshold
   * @param unicitySeal unicity seal with node signatures
   * @return verification result with per-signature details; FAIL, never an exception, for any
   *     malformed signature material
   */
  public static VerificationResult<VerificationStatus> verify(RootTrustBase trustBase,
                                                              UnicitySeal unicitySeal) {
    List<VerificationResult<?>> results = new ArrayList<>();
    Map<String, byte[]> witnesses = unicitySeal.getWitnesses();
    if (witnesses == null) {
      return new VerificationResult<>(
              "UnicitySealQuorumSignaturesVerificationRule",
              VerificationStatus.FAIL,
              "Unicity seal has no signatures",
              results
      );
    }

    DataHash hash = new DataHasher(HashAlgorithm.SHA256)
            .update(unicitySeal.toCborWithoutSignatures())
            .digest();
    long threshold = trustBase.getQuorumThreshold();
    long successful = 0;
    for (Map.Entry<String, byte[]> entry : witnesses.entrySet()) {
      VerificationResult<?> result = UnicitySealQuorumSignaturesVerificationRule.verifySignature(
              trustBase,
              entry.getKey(),
              entry.getValue(),
              hash
      );
      results.add(result);

      if (result.getStatus() == VerificationStatus.OK) {
        successful++;
        if (Long.compareUnsigned(successful, threshold) >= 0) {
          break;
        }
      }
    }

    if (Long.compareUnsigned(successful, threshold) >= 0) {
      return new VerificationResult<>(
              "UnicitySealQuorumSignaturesVerificationRule",
              VerificationStatus.OK,
              "Unicity quorum signatures verification threshold reached",
              results
      );
    }

    return new VerificationResult<>(
            "UnicitySealQuorumSignaturesVerificationRule",
            VerificationStatus.FAIL,
            "Unicity quorum treshold was not reached",
            results
    );
  }

  private static boolean isLowS(Signature signature) {
    byte[] bytes = signature.getBytes();
    BigInteger s = new BigInteger(1, Arrays.copyOfRange(bytes, 32, 64));
    return s.compareTo(HALF_ORDER) <= 0;
  }

  private static VerificationResult<?> verifySignature(
          RootTrustBase trustBase,
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

    NodeInfo node = trustBase.getRootNode(nodeId);
    if (node == null) {
      return new VerificationResult<>(
              rule,
              VerificationStatus.FAIL,
              "No root node defined"
      );
    }

    // low-s only, like the JS and Rust clients: the high-s twin of a signature recovers the same key
    // and must not count as a second encoding of the same vote
    if (!isLowS(signature)) {
      return new VerificationResult<>(rule, VerificationStatus.FAIL,
              "Signature verification failed");
    }

    boolean valid;
    try {
      valid = SigningService.verifyWithPublicKey(hash, signature, node.getSigningKey());
    } catch (RuntimeException e) {
      // attacker-controlled scalars can make the curve arithmetic throw; that is an invalid
      // signature, not an error of the caller
      valid = false;
    }
    if (!valid) {
      return new VerificationResult<>(
              rule,
              VerificationStatus.FAIL,
              "Signature verification failed"
      );
    }

    return new VerificationResult<>(rule, VerificationStatus.OK);
  }

}
