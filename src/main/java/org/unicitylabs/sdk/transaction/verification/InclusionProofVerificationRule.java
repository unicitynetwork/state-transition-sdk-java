package org.unicitylabs.sdk.transaction.verification;

import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.InclusionCertificate;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.LeafValue;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * This class provides the functionality to verify an inclusion proof against a given trust base
 * and transaction. It ensures that the inclusion proof is valid, authentic, and corresponds to
 * the specified transaction.
 *
 *  <p>The verification process involves several checks, including:
 * - Validating the trust base against the inclusion proof.
 * - Ensuring the Merkle tree path is valid and included in the committed tree.
 * - Verifying the certification data referenced by the inclusion proof.
 * - Checking that the transaction hash matches the reference in the proof.
 * - Confirming the proof's leaf value aligns with the expected hash.
 * - Verifies given predicate against certification data
 */
public class InclusionProofVerificationRule {

  /**
   * Verifies the provided inclusion proof against the specified trust base and transaction.
   *
   * @param trustBase the root trust base used to validate the inclusion proof
   * @param predicateVerifier the service responsible for evaluating transaction predicates
   * @param inclusionProof the inclusion proof containing certification data and merkle tree path
   * @param transaction the transaction that is being verified against the proof
   *
   * @return a {@code VerificationResult} object containing the {@code InclusionProofVerificationStatus}
   *         and additional details about the verification outcome
   */
  public static VerificationResult<InclusionProofVerificationStatus> verify(RootTrustBase trustBase,
                                                                            PredicateVerifierService predicateVerifier, InclusionProof inclusionProof,
                                                                            Transaction transaction) {
    return verify(trustBase, predicateVerifier, new UnicityCertificateVerifier(), inclusionProof,
            transaction);
  }

  /**
   * Verifies the provided inclusion proof with an explicit certificate verifier, so that the work
   * of an operation (certificate bodies, seals) is shared between proofs.
   *
   * @param trustBase the root trust base used to validate the inclusion proof
   * @param predicateVerifier the service responsible for evaluating transaction predicates
   * @param certificateVerifier verifier of the unicity certificate, typically an operation verifier
   * @param inclusionProof the inclusion proof containing certification data and merkle tree path
   * @param transaction the transaction that is being verified against the proof
   *
   * @return a {@code VerificationResult} object containing the {@code InclusionProofVerificationStatus}
   *         and additional details about the verification outcome
   */
  public static VerificationResult<InclusionProofVerificationStatus> verify(
          RootTrustBase trustBase,
          PredicateVerifierService predicateVerifier,
          UnicityCertificateVerifier certificateVerifier,
          InclusionProof inclusionProof,
          Transaction transaction) {
    CertificationData certificationData = inclusionProof.getCertificationData();
    long referenceTime = inclusionProof.getReferenceTime();

    if (!certificationData.getTransactionHash().equals(transaction.calculateTransactionHash())) {
      return new VerificationResult<>("InclusionProofVerificationRule",
              InclusionProofVerificationStatus.TRANSACTION_HASH_MISMATCH);
    }

    if (!certificationData.getLockScript().equals(transaction.getLockScript())
            || !certificationData.getSourceStateHash().equals(transaction.getSourceStateHash())
            || !certificationData.getExpiresAt().equals(transaction.getExpiresAt())) {
      return new VerificationResult<>("InclusionProofVerificationRule",
              InclusionProofVerificationStatus.CERTIFICATION_DATA_MISMATCH);
    }

    // Admissible only in a round strictly below the deadline; both sides are Unix seconds of
    // consensus time. Unsigned: a CBOR uint at or above 2^63 arrives as a negative long, and a
    // signed comparison would wave an expired request through.
    if (transaction.getExpiresAt().isPresent()
            && Long.compareUnsigned(referenceTime, transaction.getExpiresAt().get()) >= 0) {
      return new VerificationResult<>("InclusionProofVerificationRule",
              InclusionProofVerificationStatus.REQUEST_EXPIRED);
    }

    // A leaf cannot postdate the round that certified it, and consensus signs that timestamp.
    // One-sided: it does not detect back-dating. See aggregator-go#186.
    if (Long.compareUnsigned(
            referenceTime,
            inclusionProof.getUnicityCertificate().getInputRecord().getTimestamp()) > 0) {
      return new VerificationResult<>("InclusionProofVerificationRule",
              InclusionProofVerificationStatus.REFERENCE_TIME_AFTER_ROUND);
    }

    StateId stateId = StateId.fromTransaction(transaction);
    DataHash leafValue = LeafValue.calculate(certificationData.getTransactionHash(), referenceTime);
    // a null root is the certified empty tree: no inclusion path can lead to it
    byte[] stateRoot = inclusionProof.getUnicityCertificate().getInputRecord().getHash();
    if (stateRoot == null
            || !inclusionProof.getInclusionCertificate().verify(
                    stateId, leafValue, new DataHash(HashAlgorithm.SHA256, stateRoot))) {
      return new VerificationResult<>("InclusionProofVerificationRule",
              InclusionProofVerificationStatus.PATH_INVALID);
    }

    VerificationResult<?> result = ShardIdMatchesStateIdRule.verify(
            stateId,
            inclusionProof.getUnicityCertificate().getShardTreeCertificate()
    );
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>(
              "InclusionProofVerificationRule",
              InclusionProofVerificationStatus.SHARD_ID_MISMATCH,
              "",
              result
      );
    }

    result = certificateVerifier.verify(trustBase, inclusionProof);
    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>(
              "InclusionProofVerificationRule",
              InclusionProofVerificationStatus.INVALID_TRUSTBASE,
              "",
              result
      );
    }

    result = predicateVerifier.verify(
            transaction.getLockScript(),
            referenceTime,
            transaction.getSourceStateHash(),
            certificationData.getTransactionHash(),
            certificationData.getUnlockScript()
    );

    if (result.getStatus() != VerificationStatus.OK) {
      return new VerificationResult<>("InclusionProofVerificationRule",
              InclusionProofVerificationStatus.NOT_AUTHENTICATED, "", result);
    }

    return new VerificationResult<>("InclusionProofVerificationRule",
            InclusionProofVerificationStatus.OK);
  }
}
