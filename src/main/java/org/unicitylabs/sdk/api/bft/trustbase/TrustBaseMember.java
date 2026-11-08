package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.Arrays;
import java.util.Objects;

/** One member of a trust-base epoch: its node id, compressed secp256k1 signing key and voting weight. */
public final class TrustBaseMember {

  private final String nodeId;
  private final byte[] sigKey;
  private final long weight;

  /**
   * Create a member.
   *
   * @param nodeId node id (UTF-8, 1 to 128 bytes in a valid entry)
   * @param sigKey 33-byte compressed signing key (copied)
   * @param weight voting weight, an unsigned 64-bit number (1 to 2^40 in a valid entry)
   */
  public TrustBaseMember(String nodeId, byte[] sigKey, long weight) {
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId cannot be null");
    this.sigKey = Arrays.copyOf(Objects.requireNonNull(sigKey, "sigKey cannot be null"),
        sigKey.length);
    this.weight = weight;
  }

  /**
   * Get the node id.
   *
   * @return node id
   */
  public String getNodeId() {
    return this.nodeId;
  }

  /**
   * Get the signing key.
   *
   * @return copy of the compressed key
   */
  public byte[] getSigKey() {
    return Arrays.copyOf(this.sigKey, this.sigKey.length);
  }

  /**
   * Get the voting weight (unsigned bits; at most 2^40 in a valid entry, so never negative there).
   *
   * @return weight
   */
  public long getWeight() {
    return this.weight;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TrustBaseMember)) {
      return false;
    }
    TrustBaseMember other = (TrustBaseMember) o;
    return this.weight == other.weight && this.nodeId.equals(other.nodeId)
        && Arrays.equals(this.sigKey, other.sigKey);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.nodeId, Arrays.hashCode(this.sigKey), this.weight);
  }
}
