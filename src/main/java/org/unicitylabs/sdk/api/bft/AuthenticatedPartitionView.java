package org.unicitylabs.sdk.api.bft;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.unicitylabs.sdk.api.NetworkId;

/**
 * A partition view authenticated against its enclosing certificate and seal.
 *
 * <p>Evidence object: the constructor is not public, only {@link PartitionViewAuthenticator} builds
 * one, so holding an instance proves that the view hashed into the enclosing certificate's
 * partition path and seal hash, matched the enclosing certificate's own leaf exactly, and that the
 * complete seal was authenticated under the authenticator's authority (network, exact epoch, round
 * at least the epoch start, quorum). There is no flag or collection a caller can supply to obtain
 * one.
 *
 * <p>Immutable and independent of every object graph it was built from: it holds only immutable
 * copies taken before verification. Per-shard work is lazy and indexed: a lookup derives only that
 * shard's certificate (O(1) leaves, O(depth) hashes already held by the tree), not the whole view,
 * and nothing is derived for shards nobody asks for.
 *
 * <p>The committed scheme and configuration hashes are authenticated by the commitment itself; they
 * are not taken from the response on trust and no independent hash list is required.
 */
public final class AuthenticatedPartitionView {

  private final String authority;
  private final String policy;
  private final long partitionId;
  private final UnicityCertificate enclosing;
  private final List<PartitionViewLeaf> leaves;
  private final Map<String, Integer> index;
  private final ShardTree tree;
  private final AtomicLong derivations = new AtomicLong();

  AuthenticatedPartitionView(String authority, String policy, long partitionId,
      UnicityCertificate enclosing, List<PartitionViewLeaf> leaves, ShardTree tree) {
    this.authority = authority;
    this.policy = policy;
    this.partitionId = partitionId;
    this.enclosing = enclosing;
    this.leaves = Collections.unmodifiableList(new ArrayList<>(leaves));
    this.tree = tree;
    // the shard to position index, built once while the view is already authenticated: O(S)
    Map<String, Integer> positions = new HashMap<>();
    for (int i = 0; i < leaves.size(); i++) {
      positions.put(leaves.get(i).getShardId().toString(), i);
    }
    this.index = positions;
  }

  /**
   * Identity of the authority snapshot the seal was authenticated under.
   *
   * @return identity text
   */
  public String getAuthority() {
    return authority;
  }

  /**
   * Identity of the optional pins applied during authentication.
   *
   * @return identity text
   */
  public String getPolicy() {
    return policy;
  }

  /**
   * Partition identifier, a nonzero unsigned 32-bit number.
   *
   * @return partition id
   */
  public long getPartitionId() {
    return partitionId;
  }

  /**
   * Partition identifier as a non-negative integer.
   *
   * @return partition id
   */
  public BigInteger getPartitionIdUnsigned() {
    return BigInteger.valueOf(partitionId);
  }

  /**
   * Partition identifier in decimal.
   *
   * @return partition id
   */
  public String getPartitionIdDecimal() {
    return Long.toString(partitionId);
  }

  /**
   * Network of the authenticated seal.
   *
   * @return network id
   */
  public NetworkId getNetworkId() {
    return enclosing.getUnicitySeal().getNetworkId();
  }

  /**
   * The authenticated enclosing seal.
   *
   * @return seal
   */
  public UnicitySeal getSeal() {
    return enclosing.getUnicitySeal();
  }

  /**
   * The committed sharding scheme, empty for a single shard partition.
   *
   * @return shard ids in canonical order
   */
  public List<ShardId> getScheme() {
    if (leaves.size() == 1 && leaves.get(0).getShardId().isEmpty()) {
      return Collections.emptyList();
    }
    List<ShardId> scheme = new ArrayList<>(leaves.size());
    for (PartitionViewLeaf leaf : leaves) {
      scheme.add(leaf.getShardId());
    }
    return Collections.unmodifiableList(scheme);
  }

  /**
   * The leaves in canonical order.
   *
   * @return unmodifiable list of leaves
   */
  public List<PartitionViewLeaf> getLeaves() {
    return leaves;
  }

  /**
   * The leaf of a shard, found through the index.
   *
   * @param shard shard id
   * @return leaf
   * @throws PartitionViewException with reason TOPOLOGY if the shard is not in the view
   */
  public PartitionViewLeaf leafOf(ShardId shard) {
    return leaves.get(position(shard));
  }

  /**
   * Derives the ordinary unicity certificate of one shard: its input record and hashes, its native
   * shard path, the shared partition certificate and the identical complete master seal. Shards in
   * their initial state keep their native null input record fields.
   *
   * @param shard shard id
   * @return certificate of the shard
   * @throws PartitionViewException with reason TOPOLOGY if the shard is not in the view
   */
  public UnicityCertificate certificateOf(ShardId shard) {
    return derive(position(shard));
  }

  /**
   * Derives the certificates of every shard in canonical order. Use {@link #certificateOf} for the
   * shards that are needed.
   *
   * @return certificates
   */
  public List<UnicityCertificate> assembleCertificates() {
    List<UnicityCertificate> result = new ArrayList<>(leaves.size());
    for (int i = 0; i < leaves.size(); i++) {
      result.add(derive(i));
    }
    return result;
  }

  /**
   * Number of certificates derived so far, a hardware-independent work counter.
   *
   * @return derivations
   */
  public long getCertificateDerivations() {
    return derivations.get();
  }

  private int position(ShardId shard) {
    Integer position = index.get(shard.toString());
    if (position == null) {
      throw new PartitionViewException(PartitionViewException.Reason.TOPOLOGY,
          String.format("Shard \"%s\" is not in the authenticated view.", shard));
    }
    return position;
  }

  private UnicityCertificate derive(int position) {
    derivations.incrementAndGet();
    PartitionViewLeaf leaf = leaves.get(position);
    return new UnicityCertificate(
        leaf.getInputRecord(),
        leaf.getTechnicalRecordHash(),
        leaf.getShardConfigurationHash(),
        tree.certificate(leaf.getShardId()),
        enclosing.getUnicityTreeCertificate(),
        enclosing.getUnicitySeal());
  }
}
