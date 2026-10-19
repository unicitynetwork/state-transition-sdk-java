package org.unicitylabs.sdk.api.bft;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.unicitylabs.sdk.api.bft.verification.SealAuthenticator;
import org.unicitylabs.sdk.api.bft.verification.SealVerificationSession;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealHashMatchesWithRootHashRule;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.internal.SingleFlightMemo;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Authenticates untrusted partition view candidates.
 *
 * <p>Rebuilds the native shard tree and the enclosing partition path from the view, requires the
 * enclosing leaf to match completely, then authenticates the seal with a strict
 * {@link SealAuthenticator}. The partition must be
 * {@link AggregationPartition#AGGREGATION_PARTITION_ID}: it is fixed, never chosen by the caller or
 * the response. Pins of the {@link PartitionViewPolicy} are applied in addition and are part of the
 * identity of the authenticator.
 *
 * <p>The checks run in this order, each before the next: structure and topology of the candidate
 * (and pins), the tree and the partition path to the seal hash, the exact match of the enclosing
 * certificate's own leaf and shard path, and only then the seal under the caller's anchored
 * authority. Successful authentications are kept in a small bounded memo keyed by the identity of
 * the authenticator and the complete bytes of the view and the enclosing certificate; failures are
 * never kept. Thread safe.
 */
public final class PartitionViewAuthenticator {

  private static final int MAX_ENTRIES = 16;
  private static final int MAX_IN_FLIGHT = 16;

  private final SealAuthenticator seals;
  private final PartitionViewPolicy policy;
  private final SingleFlightMemo<AuthenticatedPartitionView> memo =
      new SingleFlightMemo<>(MAX_ENTRIES, MAX_IN_FLIGHT, view -> true);
  private final AtomicLong authentications = new AtomicLong();
  private final AtomicLong memoHits = new AtomicLong();

  /**
   * Creates an authenticator.
   *
   * @param seals strict seal authenticator owned by the caller
   * @param policy optional additional constraints, {@link PartitionViewPolicy#none()} for none
   * @throws PartitionViewException if the policy is not consistent
   */
  public PartitionViewAuthenticator(SealAuthenticator seals, PartitionViewPolicy policy) {
    this.seals = Objects.requireNonNull(seals, "seals cannot be null");
    this.policy = Objects.requireNonNull(policy, "policy cannot be null");
    policy.getIdentity();
  }

  /**
   * Identity of this authenticator: the partition, the seal authority and the pins.
   *
   * @return identity text
   */
  public String getIdentity() {
    return AggregationPartition.AGGREGATION_PARTITION_ID + "|" + seals.getIdentity() + "|"
        + policy.getIdentity();
  }

  /**
   * Authentications performed, memo hits excluded.
   *
   * @return count
   */
  public long getAuthentications() {
    return authentications.get();
  }

  /**
   * Authentications answered from the memo.
   *
   * @return count
   */
  public long getMemoHits() {
    return memoHits.get();
  }

  /**
   * Authenticates a candidate view against the certificate that encloses it.
   *
   * @param view untrusted candidate
   * @param enclosing the certificate whose commitment covers the view
   * @return authenticated view
   * @throws PartitionViewException with the failing reason; nothing is retained on failure
   */
  public AuthenticatedPartitionView authenticate(PartitionView view, UnicityCertificate enclosing) {
    Objects.requireNonNull(view, "view cannot be null");
    Objects.requireNonNull(enclosing, "enclosing cannot be null");
    // both are immutable: what is checked below is what the result holds
    byte[] viewBytes = view.toCbor();
    byte[] enclosingBytes = enclosing.toCbor();
    String key = HexConverter.encode(new DataHasher(HashAlgorithm.SHA256)
        .update(CborSerializer.encodeArray(
            CborSerializer.encodeTextString(getIdentity()),
            CborSerializer.encodeByteString(viewBytes),
            CborSerializer.encodeByteString(enclosingBytes)))
        .digest().getData());
    SingleFlightMemo.Outcome<AuthenticatedPartitionView> outcome =
        memo.get(key, () -> run(view, enclosing));
    if (outcome.getSource() != SingleFlightMemo.Source.COMPUTED) {
      memoHits.incrementAndGet();
    }
    return outcome.getValue();
  }

  /**
   * Authenticates a candidate view given as bytes against the encoded certificate that encloses
   * it. Both must be strictly canonical, so that derived certificates carry exactly the master seal
   * bytes that were authenticated.
   *
   * @param viewBytes candidate view
   * @param enclosingBytes enclosing unicity certificate
   * @return authenticated view
   * @throws PartitionViewException with the failing reason
   */
  public AuthenticatedPartitionView authenticate(byte[] viewBytes, byte[] enclosingBytes) {
    return authenticate(PartitionView.fromCbor(viewBytes), decodeEnclosing(enclosingBytes));
  }

  /**
   * Decodes the enclosing certificate of a view, rejecting any encoding that does not re-encode
   * byte-identically.
   *
   * @param bytes CBOR bytes of a unicity certificate
   * @return the certificate
   * @throws PartitionViewException with reason ENCODING
   */
  public static UnicityCertificate decodeEnclosing(byte[] bytes) {
    UnicityCertificate certificate;
    try {
      certificate = UnicityCertificate.fromCbor(bytes);
    } catch (CborSerializationException | IllegalArgumentException e) {
      throw new PartitionViewException(PartitionViewException.Reason.ENCODING,
          "Decoding enclosing unicity certificate: " + e.getMessage(), e);
    }
    if (!Arrays.equals(certificate.toCbor(), bytes)) {
      throw new PartitionViewException(PartitionViewException.Reason.ENCODING,
          "Enclosing unicity certificate is not canonically encoded.");
    }
    return certificate;
  }

  private AuthenticatedPartitionView run(PartitionView view, UnicityCertificate enclosing) {
    authentications.incrementAndGet();

    // 1: structure and topology of the candidate, pins
    view.validateStructure(AggregationPartition.AGGREGATION_PARTITION_ID, policy);
    ShardTree tree;
    try {
      tree = ShardTree.create(view.getLeaves());
    } catch (IllegalArgumentException e) {
      throw new PartitionViewException(PartitionViewException.Reason.TOPOLOGY, e.getMessage(), e);
    }

    // 2: the partition certificate is the view's, the view's own tree evaluates to the seal hash
    UnicityTreeCertificate partition = enclosing.getUnicityTreeCertificate();
    if (partition.getPartitionIdentifierUnsigned() != view.getPartitionId()) {
      throw new PartitionViewException(PartitionViewException.Reason.COMMITMENT, String.format(
          "Enclosing certificate is for partition %d, view is for %d.",
          partition.getPartitionIdentifierUnsigned(), view.getPartitionId()));
    }

    // 3: the enclosing certificate's own leaf and native shard path, complete
    ShardId own = enclosing.getShardTreeCertificate().getShard();
    PartitionViewLeaf leaf = null;
    for (PartitionViewLeaf candidate : view.getLeaves()) {
      if (candidate.getShardId().equals(own)) {
        leaf = candidate;
        break;
      }
    }
    if (leaf == null) {
      throw commitment(String.format("Enclosing certificate shard \"%s\" is not in the view.", own));
    }
    ShardTreeCertificate ownPath = tree.certificate(own);
    if (!ownPath.equals(enclosing.getShardTreeCertificate())
        || enclosing.getShardTreeCertificate().getSiblingHashCount() != own.getLength()) {
      throw commitment("Enclosing certificate shard tree certificate does not match the view.");
    }
    if (!Arrays.equals(enclosing.getInputRecord().toCbor(), leaf.getInputRecord().toCbor())) {
      throw commitment("Enclosing certificate input record does not match the view leaf.");
    }
    if (!Arrays.equals(enclosing.getTechnicalRecordHash(), leaf.getTechnicalRecordHash())) {
      throw commitment("Enclosing certificate TRHash does not match the view leaf.");
    }
    if (!Arrays.equals(enclosing.getShardConfigurationHash(), leaf.getShardConfigurationHash())) {
      throw commitment(
          "Enclosing certificate shard configuration hash does not match the view leaf.");
    }

    String sealFailure = SealVerificationSession.checkSealStructure(enclosing.getUnicitySeal());
    if (sealFailure != null) {
      throw new PartitionViewException(PartitionViewException.Reason.AUTHENTICATION,
          "Enclosing certificate: " + sealFailure);
    }

    UnicityCertificate derived = new UnicityCertificate(
        leaf.getInputRecord(), leaf.getTechnicalRecordHash(), leaf.getShardConfigurationHash(),
        tree.certificate(leaf.getShardId()), partition, enclosing.getUnicitySeal());
    VerificationResult<VerificationStatus> hash =
        UnicitySealHashMatchesWithRootHashRule.verify(derived);
    if (hash.getStatus() != VerificationStatus.OK) {
      throw commitment("Partition view does not match the seal hash.");
    }

    // 4: only then the seal, under the caller's anchored authority
    VerificationResult<VerificationStatus> sealResult = seals.authenticate(enclosing.getUnicitySeal());
    if (sealResult.getStatus() != VerificationStatus.OK) {
      throw new PartitionViewException(PartitionViewException.Reason.AUTHENTICATION,
          "Authenticating unicity seal: "
              + (sealResult.getMessage().isEmpty() ? "failed" : sealResult.getMessage()) + ".");
    }

    List<PartitionViewLeaf> leaves = view.getLeaves();
    return new AuthenticatedPartitionView(seals.getIdentity(), policy.getIdentity(),
        view.getPartitionId(), enclosing, leaves, tree);
  }

  private static PartitionViewException commitment(String message) {
    return new PartitionViewException(PartitionViewException.Reason.COMMITMENT, message);
  }
}
