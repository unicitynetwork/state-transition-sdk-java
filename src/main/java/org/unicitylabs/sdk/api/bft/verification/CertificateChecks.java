package org.unicitylabs.sdk.api.bft.verification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealHashMatchesWithRootHashRule;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * The checks of a certificate that do not depend on the policy applied to its seal: network,
 * fixed partition, body structure, seal structure and the paths to the seal hash. Shared by the
 * static legacy verification and the operation verifier so that both judge a body identically.
 */
final class CertificateChecks {

  static final String NETWORK_RULE = "UnicitySealNetworkMatchesTrustBaseRule";
  static final String PARTITION_RULE = "UnicityCertificatePartitionRule";
  static final String BODY_RULE = "UnicityCertificateBodyRule";
  static final String SEAL_STRUCTURE_RULE = "UnicitySealStructureRule";
  static final String PINS_RULE = "UnicityCertificatePinsRule";

  /** Outcome of the checks of one certificate body: a typed failure or null, and the rules run. */
  static final class Body {
    private final UnicityCertificateFailure failure;
    private final List<VerificationResult<?>> rules;

    Body(UnicityCertificateFailure failure, List<VerificationResult<?>> rules) {
      this.failure = failure;
      this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
    }

    UnicityCertificateFailure getFailure() {
      return failure;
    }

    List<VerificationResult<?>> getRules() {
      return rules;
    }

    boolean isOk() {
      return failure == null;
    }
  }

  private CertificateChecks() {
  }

  /**
   * Network of the seal against the trusted authority.
   *
   * @return the rule result
   */
  static VerificationResult<VerificationStatus> network(RootTrustBase trustBase,
      UnicityCertificate certificate) {
    boolean match = certificate.getUnicitySeal().getNetworkId().equals(trustBase.getNetworkId());
    return new VerificationResult<>(NETWORK_RULE, match ? VerificationStatus.OK
        : VerificationStatus.FAIL);
  }

  /**
   * The fixed partition, in front of any memo lookup: a hit can never carry another partition.
   *
   * @return the rule result
   */
  static VerificationResult<VerificationStatus> partition(UnicityCertificate certificate) {
    if (certificate.checkBody() == UnicityCertificate.BodyViolation.PARTITION) {
      return new VerificationResult<>(PARTITION_RULE, VerificationStatus.FAIL,
          UnicityCertificate.BodyViolation.PARTITION.getDescription());
    }
    return new VerificationResult<>(PARTITION_RULE, VerificationStatus.OK);
  }

  /**
   * Body structure, seal structure and the shard and partition paths to the seal hash. A pure
   * function of the complete certificate, so its success is memoizable under the certificate's
   * complete bytes.
   *
   * @return the first failure and the rules run
   */
  static Body body(UnicityCertificate certificate) {
    List<VerificationResult<?>> rules = new ArrayList<>();

    UnicityCertificate.BodyViolation violation = certificate.checkBody();
    if (violation != null) {
      String message = violation == UnicityCertificate.BodyViolation.INPUT_RECORD
          ? "Invalid input record: " + certificate.getInputRecord().structureFailure()
          : violation.getDescription();
      rules.add(new VerificationResult<>(
          violation == UnicityCertificate.BodyViolation.PARTITION ? PARTITION_RULE : BODY_RULE,
          VerificationStatus.FAIL, message));
      UnicityCertificateFailure failure;
      switch (violation) {
        case PARTITION:
          failure = UnicityCertificateFailure.PARTITION_MISMATCH;
          break;
        case SHARD_PATH_LENGTH:
          failure = UnicityCertificateFailure.SHARD_PATH_INVALID;
          break;
        default:
          failure = UnicityCertificateFailure.BODY_INVALID;
          break;
      }
      return new Body(failure, rules);
    }
    rules.add(new VerificationResult<>(BODY_RULE, VerificationStatus.OK));

    String sealStructure = SealVerificationSession.checkSealStructure(certificate.getUnicitySeal());
    if (sealStructure != null) {
      rules.add(new VerificationResult<>(SEAL_STRUCTURE_RULE, VerificationStatus.FAIL,
          sealStructure));
      return new Body(UnicityCertificateFailure.MALFORMED_SEAL, rules);
    }
    rules.add(new VerificationResult<>(SEAL_STRUCTURE_RULE, VerificationStatus.OK));

    VerificationResult<VerificationStatus> hash =
        UnicitySealHashMatchesWithRootHashRule.verify(certificate);
    rules.add(hash);
    if (hash.getStatus() != VerificationStatus.OK) {
      return new Body(UnicityCertificateFailure.SEAL_HASH_MISMATCH, rules);
    }
    return new Body(null, rules);
  }

  /**
   * Caller pins against the certificate's shard and configuration hash.
   *
   * @return the rule result
   */
  static VerificationResult<VerificationStatus> pins(PartitionViewPolicy pins,
      UnicityCertificate certificate) {
    String violation = pins.violation(certificate.getShardTreeCertificate().getShard(),
        certificate.getShardConfigurationHash());
    if (violation != null) {
      return new VerificationResult<>(PINS_RULE, VerificationStatus.FAIL, violation);
    }
    return new VerificationResult<>(PINS_RULE, VerificationStatus.OK);
  }
}
