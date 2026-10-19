package org.unicitylabs.sdk.consolidation;

import java.util.Objects;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.InclusionProof;

/**
 * One original outer-history proof: the signed certification data and the proof that certified it.
 */
public final class ConsolidationItem {

  private final CertificationData certificationData;
  private final InclusionProof proof;

  /**
   * Creates an item.
   *
   * @param certificationData the signed certification data
   * @param proof the proof that certified it
   */
  public ConsolidationItem(CertificationData certificationData, InclusionProof proof) {
    this.certificationData = Objects.requireNonNull(certificationData,
        "certificationData cannot be null");
    this.proof = Objects.requireNonNull(proof, "proof cannot be null");
  }

  /**
   * The signed certification data.
   *
   * @return certification data
   */
  public CertificationData getCertificationData() {
    return certificationData;
  }

  /**
   * The original proof.
   *
   * @return proof
   */
  public InclusionProof getProof() {
    return proof;
  }
}
