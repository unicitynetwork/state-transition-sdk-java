package org.unicitylabs.sdk.api.bft;

import java.util.Objects;
import org.unicitylabs.sdk.api.bft.verification.SealAuthenticator;

/**
 * Authenticates untrusted partition view candidates.
 *
 * <p>Rebuilds the native shard tree and the enclosing partition path from the view, requires the
 * enclosing leaf to match completely, then authenticates the seal with a strict
 * {@link SealAuthenticator}. The partition must be
 * {@link AggregationPartition#AGGREGATION_PARTITION_ID}. Pins of the {@link PartitionViewPolicy}
 * are applied in addition and are part of the identity of the authenticator.
 */
public final class PartitionViewAuthenticator {

  private final SealAuthenticator seals;
  private final PartitionViewPolicy policy;

  /**
   * Creates an authenticator.
   *
   * @param seals strict seal authenticator owned by the caller
   * @param policy optional additional constraints, {@link PartitionViewPolicy#none()} for none
   */
  public PartitionViewAuthenticator(SealAuthenticator seals, PartitionViewPolicy policy) {
    this.seals = Objects.requireNonNull(seals, "seals cannot be null");
    this.policy = Objects.requireNonNull(policy, "policy cannot be null");
  }

  /**
   * Identity of this authenticator: the seal authority and the pins.
   *
   * @return identity text
   */
  public String getIdentity() {
    throw new UnsupportedOperationException("Implemented by package A");
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
    throw new UnsupportedOperationException("Implemented by package A");
  }
}
