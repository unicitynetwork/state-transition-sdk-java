package org.unicitylabs.sdk.api;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.api.NonInclusionVerificationException.Reason;
import org.unicitylabs.sdk.api.bft.AggregationPartition;
import org.unicitylabs.sdk.api.bft.InputRecord;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.ShardId;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.api.bft.verification.SealAuthenticator;
import org.unicitylabs.sdk.api.bft.verification.SealVerificationSession;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealHashMatchesWithRootHashRule;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.util.UnsignedLongs;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Verifies non-inclusion proofs and reports the authenticated snapshot they speak about.
 *
 * <p>Nothing is exposed before the exact profile, the shard and partition commitments, the trusted
 * network, authority, epoch interval and quorum, the query binding and the complete state-tree
 * relation are all established. A rejection throws {@link NonInclusionVerificationException} with a
 * rejection class and never carries a snapshot.
 *
 * <p>Non-inclusion proves absence only at the authenticated snapshot returned by verification. It
 * does not prove absence now, continuous absence, or that a token can be spent. Compare the
 * snapshot&rsquo;s network and root round (or its certified seal timestamp under your
 * application&rsquo;s time policy) with an independently trusted requirement. The seal timestamp is a
 * consensus-certified time, not proof of real-time freshness. Do not use unverified response
 * metadata for this check.
 *
 * <p>Several proofs under one operation share one {@link SealAuthenticator} (for example a
 * {@link SealVerificationSession}), so a seal common to them is authenticated once.
 */
public final class NonInclusionVerifier {

  /** The warning text that accompanies every non-inclusion entry point. */
  public static final String SNAPSHOT_WARNING =
      "Non-inclusion proves absence only at the authenticated snapshot returned by verification. "
          + "It does not prove absence now, continuous absence, or that a token can be spent. "
          + "Compare the snapshot’s network and root round (or its certified seal timestamp "
          + "under your application’s time policy) with an independently trusted "
          + "requirement. The seal timestamp is a consensus-certified time, not proof of "
          + "real-time freshness. Do not use unverified response metadata for this check.";

  private NonInclusionVerifier() {
  }

  /**
   * Verifies a proof against the trust base, under the strict policy.
   *
   * <p>Non-inclusion proves absence only at the authenticated snapshot returned by verification.
   * It does not prove absence now, continuous absence, or that a token can be spent. Compare the
   * snapshot&rsquo;s network and root round (or its certified seal timestamp under your
   * application&rsquo;s time policy) with an independently trusted requirement. The seal timestamp
   * is a consensus-certified time, not proof of real-time freshness. Do not use unverified response
   * metadata for this check.
   *
   * @param trustBase the authority
   * @param proof the unverified proof, bound to the queried state
   * @param context what the caller trusts about the shard
   * @return the outcome with its authenticated snapshot
   * @throws NonInclusionVerificationException if the proof is rejected; no snapshot is attached
   */
  public static NonInclusionOutcome verify(RootTrustBase trustBase, NonInclusionProof proof,
      NonInclusionContext context) {
    Objects.requireNonNull(trustBase, "trustBase cannot be null");
    return verify(SealVerificationSession.create(trustBase, VerificationPolicy.STRICT), proof,
        context);
  }

  /**
   * Verifies a proof for an explicit state: the state must be the one the proof is bound to.
   *
   * <p>Non-inclusion proves absence only at the authenticated snapshot returned by verification.
   * It does not prove absence now, continuous absence, or that a token can be spent. Compare the
   * snapshot&rsquo;s network and root round (or its certified seal timestamp under your
   * application&rsquo;s time policy) with an independently trusted requirement. The seal timestamp
   * is a consensus-certified time, not proof of real-time freshness. Do not use unverified response
   * metadata for this check.
   *
   * @param seals strict seal authenticator of the caller&rsquo;s authority
   * @param proof the unverified proof
   * @param target the state asked about
   * @param context what the caller trusts about the shard
   * @return the outcome with its authenticated snapshot
   * @throws NonInclusionVerificationException if the proof is rejected or bound to another state
   */
  public static NonInclusionOutcome verify(SealAuthenticator seals, NonInclusionProof proof,
      StateId target, NonInclusionContext context) {
    Objects.requireNonNull(target, "target cannot be null");
    Objects.requireNonNull(proof, "proof cannot be null");
    if (!Arrays.equals(proof.getStateId().getData(), target.getData())) {
      throw new NonInclusionVerificationException(Reason.TARGET_MISMATCH,
          "the proof is bound to another state", null);
    }
    return verify(seals, proof, context);
  }

  /**
   * Verifies a proof for the state it is bound to, using a caller-owned strict seal authenticator.
   *
   * <p>Non-inclusion proves absence only at the authenticated snapshot returned by verification.
   * It does not prove absence now, continuous absence, or that a token can be spent. Compare the
   * snapshot&rsquo;s network and root round (or its certified seal timestamp under your
   * application&rsquo;s time policy) with an independently trusted requirement. The seal timestamp
   * is a consensus-certified time, not proof of real-time freshness. Do not use unverified response
   * metadata for this check.
   *
   * @param seals strict seal authenticator of the caller&rsquo;s authority
   * @param proof the unverified proof, bound to the queried state
   * @param context what the caller trusts about the shard
   * @return the outcome with its authenticated snapshot
   * @throws NonInclusionVerificationException if the proof is rejected; no snapshot is attached
   */
  public static NonInclusionOutcome verify(SealAuthenticator seals, NonInclusionProof proof,
      NonInclusionContext context) {
    Objects.requireNonNull(seals, "seals cannot be null");
    Objects.requireNonNull(proof, "proof cannot be null");
    Objects.requireNonNull(context, "context cannot be null");

    NonInclusionProof.Decoded decoded;
    try {
      decoded = proof.decode();
    } catch (CborSerializationException e) {
      throw new NonInclusionVerificationException(Reason.MALFORMED, e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new NonInclusionVerificationException(Reason.MALFORMED,
          "the proof could not be decoded", e);
    }
    UnicityCertificate uc = decoded.unicityCertificate;
    NonInclusionCertificate certificate = decoded.certificate;
    byte[] key = proof.getStateId().getData();

    authenticateCertificate(seals, uc, context);

    InputRecord record = uc.getInputRecord();
    byte[] stateRoot = record.getHash();

    ShardId shard = uc.getShardTreeCertificate().getShard();
    if (shard.getLength() != 0) {
      byte[] terminal = certificate.getTerminalKey();
      if (!shard.isPrefixOf(key) || (terminal != null && !shard.isPrefixOf(terminal))) {
        throw new NonInclusionVerificationException(Reason.CERTIFICATE_INVALID,
            "the query or the terminal leaf is outside the certified shard", null);
      }
    }
    if (!certificate.authenticates(key, stateRoot)) {
      throw new NonInclusionVerificationException(Reason.CERTIFICATE_INVALID,
          "the path does not reach the certified state root", null);
    }

    UnicitySeal seal = uc.getUnicitySeal();
    NonInclusionSnapshot snapshot = new NonInclusionSnapshot(
        seal.getNetworkId(),
        Integer.toUnsignedLong(uc.getUnicityTreeCertificate().getPartitionIdentifier()),
        shard,
        seal.getRootChainRoundNumber(),
        seal.getTimestamp(),
        seal.getEpoch(),
        record.getRoundNumber(),
        record.getEpoch(),
        stateRoot);
    return new NonInclusionOutcome(
        certificate.terminalIs(key) ? NonInclusionOutcome.Kind.INCLUDED_AT_SNAPSHOT
            : NonInclusionOutcome.Kind.ABSENT_AT_SNAPSHOT,
        proof.getStateId(), snapshot);
  }

  /**
   * Authenticates a bare unicity certificate strictly: fixed partition, body, shard path, the
   * context&rsquo;s shard and configuration pin, the path to the seal hash, the seal and the
   * authority&rsquo;s end.
   *
   * <p>This is the single seam to the shared certificate verification of package A (raw
   * certificate verification sharing the strict policy, session and pins); until that lands it is
   * assembled from the existing rules and {@link SealAuthenticator}.
   */
  private static void authenticateCertificate(SealAuthenticator seals, UnicityCertificate uc,
      NonInclusionContext context) {
    // the fixed partition first: no memo, pin or signature can make another partition valid
    long partition = Integer.toUnsignedLong(uc.getUnicityTreeCertificate().getPartitionIdentifier());
    if (partition != AggregationPartition.AGGREGATION_PARTITION_ID) {
      throw invalid("the partition is not the aggregation partition");
    }
    String bodyFault = checkBody(uc);
    if (bodyFault != null) {
      throw invalid(bodyFault);
    }
    ShardId shard = uc.getShardTreeCertificate().getShard();
    List<byte[]> path = uc.getShardTreeCertificate().getSiblingHashList();
    if (path.size() != shard.getLength()) {
      throw invalid("the shard path has not one sibling per shard bit");
    }
    if (!shard.equals(context.getShard())) {
      throw invalid("the certificate is not of the context's shard");
    }
    byte[] pin = context.getShardConfigurationHash();
    if (pin != null && !Arrays.equals(pin, uc.getShardConfigurationHash())) {
      throw invalid("the shard configuration hash differs from the pinned one");
    }
    try {
      if (UnicitySealHashMatchesWithRootHashRule.verify(uc).getStatus() != VerificationStatus.OK) {
        throw invalid("the shard and partition paths do not reach the seal hash");
      }
    } catch (NonInclusionVerificationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new NonInclusionVerificationException(Reason.UC_INVALID,
          "the shard and partition paths cannot be evaluated", e);
    }

    UnicitySeal seal = uc.getUnicitySeal();
    if (seals.authenticate(seal).getStatus() != VerificationStatus.OK) {
      throw new NonInclusionVerificationException(Reason.AUTHORITY_REJECTED,
          "the seal is not authenticated by the trusted authority", null);
    }
    // the end of the authority is a statement about an authenticated seal
    if (context.hasAuthorityEndRound() && !UnsignedLongs.lessThan(
        seal.getRootChainRoundNumber(), context.getAuthorityEndRound())) {
      throw new NonInclusionVerificationException(Reason.AUTHORITY_REJECTED,
          "the seal is not before the end of the authority", null);
    }
  }

  /**
   * Structural rules of the input record and technical record hash, as the reference
   * implementation requires of an ordinary certificate. Null and empty byte strings are equal where
   * the reference compares lengths.
   *
   * @return the violated rule, null if the body is valid
   */
  private static String checkBody(UnicityCertificate uc) {
    InputRecord ir = uc.getInputRecord();
    if (ir.getRoundNumber() != 0) {
      if (ir.getSummaryValue() == null) {
        return "the summary value is missing";
      }
      if (ir.getTimestamp() == 0) {
        return "the timestamp is unassigned";
      }
    }
    byte[] root = ir.getHash();
    if (root != null && root.length != 32) {
      return "the state root is neither null nor 32 bytes";
    }
    byte[] previous = ir.getPreviousHash();
    boolean sameState = Arrays.equals(previous == null ? new byte[0] : previous,
        root == null ? new byte[0] : root);
    byte[] block = ir.getBlockHash();
    boolean blockEmpty = block == null || block.length == 0;
    if (sameState && !blockEmpty) {
      return "the state did not change but a block hash is present";
    }
    if (!sameState && blockEmpty) {
      return "the state changed but the block hash is missing";
    }
    byte[] technical = uc.getTechnicalRecordHash();
    if (technical == null || technical.length != 32) {
      return "the technical record hash is not 32 bytes";
    }
    return null;
  }

  private static NonInclusionVerificationException invalid(String message) {
    return new NonInclusionVerificationException(Reason.UC_INVALID, message, null);
  }
}
