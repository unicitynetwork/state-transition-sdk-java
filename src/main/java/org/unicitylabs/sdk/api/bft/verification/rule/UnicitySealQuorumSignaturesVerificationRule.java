package org.unicitylabs.sdk.api.bft.verification.rule;

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
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Rule to verify that the UnicitySeal contains valid quorum signatures.
 */
public class UnicitySealQuorumSignaturesVerificationRule {

  private UnicitySealQuorumSignaturesVerificationRule() {}

  /**
   * Verifies unicity seal signatures and checks that the quorum threshold is reached.
   *
   * @param trustBase trust base containing root nodes and quorum threshold
   * @param unicitySeal unicity seal with node signatures
   * @return verification result with per-signature details
   */
  public static VerificationResult<VerificationStatus> verify(RootTrustBase trustBase,
      UnicitySeal unicitySeal) {
    List<VerificationResult<?>> results = new ArrayList<>();
    DataHash hash = new DataHasher(HashAlgorithm.SHA256)
        .update(unicitySeal.toCborWithoutSignatures())
        .digest();
    int successful = 0;
    for (Map.Entry<String, byte[]> entry : unicitySeal.getSignatures().entrySet()) {
      String nodeId = entry.getKey();
      byte[] signature = entry.getValue();

      VerificationResult<?> result = UnicitySealQuorumSignaturesVerificationRule.verifySignature(
          trustBase,
          nodeId,
          signature,
          hash.getData()
      );
      results.add(result);

      if (result.getStatus() == VerificationStatus.OK) {
        successful++;
      }
    }

    if (successful >= trustBase.getQuorumThreshold()) {
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

  private static VerificationResult<?> verifySignature(
      RootTrustBase trustBase,
      String nodeId,
      byte[] signature,
      byte[] hash
  ) {
    NodeInfo node = trustBase.getRootNodes().stream()
        .filter(n -> n.getNodeId().equals(nodeId))
        .findFirst()
        .orElse(null);

    if (node == null) {
      return new VerificationResult<>(
          String.format("SignatureVerificationRule[%s]", nodeId),
          VerificationStatus.FAIL,
          "No root node defined"
      );
    }

    if (!SigningService.verifyWithPublicKey(
        hash,
        Arrays.copyOf(signature, signature.length - 1),
        node.getSigningKey()
    )) {
      return new VerificationResult<>(
          String.format("SignatureVerificationRule[%s]", nodeId),
          VerificationStatus.FAIL,
          "Signature verification failed"
      );
    }

    return new VerificationResult<>(
        String.format("SignatureVerificationRule[%s]", nodeId),
        VerificationStatus.OK
    );
  }

}
