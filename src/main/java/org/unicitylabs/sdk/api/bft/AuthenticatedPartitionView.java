package org.unicitylabs.sdk.api.bft;

import java.util.List;
import org.unicitylabs.sdk.api.NetworkId;

/**
 * A partition view authenticated against its enclosing certificate and seal.
 *
 * <p>Evidence object: the constructor is not public, only {@link PartitionViewAuthenticator} builds
 * one, so holding an instance proves the authentication happened under the authenticator's
 * authority and policy. Indexed, immutable leaves; per-shard certificates are derived lazily and
 * only when requested.
 */
public final class AuthenticatedPartitionView {

  private AuthenticatedPartitionView() {
  }

  /**
   * Identity of the authority and policy the view was authenticated under.
   *
   * @return identity text
   */
  public String getAuthority() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Partition identifier as unsigned bits.
   *
   * @return partition id
   */
  public long getPartitionId() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Network of the enclosing seal.
   *
   * @return network id
   */
  public NetworkId getNetworkId() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * The authenticated enclosing seal.
   *
   * @return seal
   */
  public UnicitySeal getSeal() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * The complete shard scheme, in canonical order.
   *
   * @return shard ids
   */
  public List<ShardId> getScheme() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * The leaves in canonical order.
   *
   * @return unmodifiable list of leaves
   */
  public List<PartitionViewLeaf> getLeaves() {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * The leaf of a shard.
   *
   * @param shard shard id
   * @return leaf
   * @throws PartitionViewException with reason TOPOLOGY if the shard is not in the view
   */
  public PartitionViewLeaf leafOf(ShardId shard) {
    throw new UnsupportedOperationException("Implemented by package A");
  }

  /**
   * Derives the ordinary unicity certificate of one shard.
   *
   * @param shard shard id
   * @return certificate of the shard
   * @throws PartitionViewException with reason TOPOLOGY if the shard is not in the view
   */
  public UnicityCertificate certificateOf(ShardId shard) {
    throw new UnsupportedOperationException("Implemented by package A");
  }
}
