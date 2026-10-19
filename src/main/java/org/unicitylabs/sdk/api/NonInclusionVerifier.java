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
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerificationResult;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.VerificationPolicy;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.util.UnsignedLongs;
import org.unicitylabs.sdk.util.verification.VerificationResult;
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
 * <p>Several proofs under one operation share one operation verifier (see
 * {@link UnicityCertificateVerifier#beginOperation}), so a seal and a certificate common to them
 * are verified once.
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
   * Verifies a proof against the trust base, under the strict policy, in a new operation.
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
    return verify(new UnicityCertificateVerifier()
        .beginOperation(VerificationPolicy.STRICT, PartitionViewPolicy.none()),
        trustBase, proof, context);
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
   * @param verifier the verifier of the operation, from {@code beginOperation(STRICT, ...)}
   * @param trustBase the authority
   * @param proof the unverified proof
   * @param target the state asked about
   * @param context what the caller trusts about the shard
   * @return the outcome with its authenticated snapshot
   * @throws NonInclusionVerificationException if the proof is rejected or bound to another state
   */
  public static NonInclusionOutcome verify(UnicityCertificateVerifier verifier,
      RootTrustBase trustBase, NonInclusionProof proof, StateId target,
      NonInclusionContext context) {
    Objects.requireNonNull(target, "target cannot be null");
    Objects.requireNonNull(proof, "proof cannot be null");
    if (!Arrays.equals(proof.getStateId().getData(), target.getData())) {
      throw new NonInclusionVerificationException(Reason.TARGET_MISMATCH,
          "the proof is bound to another state", null);
    }
    return verify(verifier, trustBase, proof, context);
  }

  /**
   * Verifies a proof for the state it is bound to within an operation.
   *
   * <p>Non-inclusion proves absence only at the authenticated snapshot returned by verification.
   * It does not prove absence now, continuous absence, or that a token can be spent. Compare the
   * snapshot&rsquo;s network and root round (or its certified seal timestamp under your
   * application&rsquo;s time policy) with an independently trusted requirement. The seal timestamp
   * is a consensus-certified time, not proof of real-time freshness. Do not use unverified response
   * metadata for this check.
   *
   * @param verifier the verifier of the operation, obtained from
   *     {@link UnicityCertificateVerifier#beginOperation} with {@link VerificationPolicy#STRICT};
   *     the plain verifier applies the legacy quorum policy and is refused
   * @param trustBase the authority
   * @param proof the unverified proof, bound to the queried state
   * @param context what the caller trusts about the shard
   * @return the outcome with its authenticated snapshot
   * @throws IllegalArgumentException if the plain (legacy) verifier is passed
   * @throws NonInclusionVerificationException if the proof is rejected; no snapshot is attached
   */
  public static NonInclusionOutcome verify(UnicityCertificateVerifier verifier,
      RootTrustBase trustBase, NonInclusionProof proof, NonInclusionContext context) {
    Objects.requireNonNull(verifier, "verifier cannot be null");
    Objects.requireNonNull(trustBase, "trustBase cannot be null");
    Objects.requireNonNull(proof, "proof cannot be null");
    Objects.requireNonNull(context, "context cannot be null");
    if (verifier.getClass() == UnicityCertificateVerifier.class) {
      throw new IllegalArgumentException(
          "The plain verifier applies the legacy quorum policy; use beginOperation(STRICT, ...).");
    }

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

    authenticateCertificate(verifier, trustBase, uc, context);

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
   * Authenticates a bare unicity certificate strictly: the fixed partition, the body, the shard
   * path, the context&rsquo;s shard and configuration pin, then the operation verifier (path to the
   * seal hash, network, epoch interval and quorum), and the authority&rsquo;s end.
   */
  private static void authenticateCertificate(UnicityCertificateVerifier verifier,
      RootTrustBase trustBase, UnicityCertificate uc, NonInclusionContext context) {
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

    UnicityCertificateVerificationResult result;
    try {
      result = verifier.verifyCertificate(trustBase, uc);
    } catch (UnsupportedOperationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new NonInclusionVerificationException(Reason.UC_INVALID,
          "the certificate could not be evaluated", e);
    }
    if (result.getStatus() != VerificationStatus.OK) {
      // a failing path to the seal hash is a fault of the certificate, anything else (network,
      // epoch, quorum) is the authority's refusal
      throw new NonInclusionVerificationException(
          failed(result, "UnicitySealHashMatchesWithRootHashRule") ? Reason.UC_INVALID
              : Reason.AUTHORITY_REJECTED,
          "the certificate is not authenticated by the trusted authority", null);
    }
    // the end of the authority is a statement about an authenticated seal
    long round = uc.getUnicitySeal().getRootChainRoundNumber();
    if (context.hasAuthorityEndRound()
        && !UnsignedLongs.lessThan(round, context.getAuthorityEndRound())) {
      throw new NonInclusionVerificationException(Reason.AUTHORITY_REJECTED,
          "the seal is not before the end of the authority", null);
    }
  }

  private static boolean failed(VerificationResult<?> result, String rule) {
    if (rule.equals(result.getRule()) && result.getStatus() == VerificationStatus.FAIL) {
      return true;
    }
    for (VerificationResult<?> child : result.getResults()) {
      if (failed(child, rule)) {
        return true;
      }
    }
    return false;
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
