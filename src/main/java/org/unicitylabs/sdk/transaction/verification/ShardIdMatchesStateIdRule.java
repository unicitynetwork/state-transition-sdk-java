package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.api.bft.ShardTreeCertificate;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Rule to verify that the shard id of the shard tree certificate is a prefix of the transaction
 * state id. An empty shard id matches any state id.
 */
public class ShardIdMatchesStateIdRule {

  private ShardIdMatchesStateIdRule() {
  }

  /**
   * Verify that the shard id is a prefix of the state id.
   *
   * @param stateId state id of the transaction being verified
   * @param shardTreeCertificate shard tree certificate carrying the shard id
   *
   * @return verification result with {@link VerificationStatus#OK} on match (or empty shard id),
   *     otherwise {@link VerificationStatus#FAIL}
   */
  public static VerificationResult<VerificationStatus> verify(
          StateId stateId,
          ShardTreeCertificate shardTreeCertificate
  ) {
    if (stateId == null) {
      return new VerificationResult<>("ShardIdMatchesStateIdRule", VerificationStatus.FAIL, "State ID is missing.");
    }

    if (shardTreeCertificate == null) {
      return new VerificationResult<>("ShardIdMatchesStateIdRule", VerificationStatus.FAIL, "Shard tree certificate is missing.");
    }

    ShardId shardId = shardTreeCertificate.getShard();
    if (shardId.getLength() == 0) {
      return new VerificationResult<>("ShardIdMatchesStateIdRule", VerificationStatus.OK);
    }

    if (!shardId.isPrefixOf(stateId.getData())) {
      return new VerificationResult<>("ShardIdMatchesStateIdRule", VerificationStatus.FAIL);
    }

    return new VerificationResult<>("ShardIdMatchesStateIdRule", VerificationStatus.OK);
  }
}
