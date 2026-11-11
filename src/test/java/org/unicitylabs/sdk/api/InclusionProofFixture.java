package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.UnicityCertificateUtils;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;

public class InclusionProofFixture {
  public static InclusionProofResponse createResponse(CertificationData certificationData,
          long referenceTime, InclusionCertificate inclusionCertificate, DataHash root,
          SigningService signingService, long certificateTimestamp) {
    UnicityCertificate unicityCertificate = UnicityCertificateUtils.generateCertificate(
            signingService, root, certificateTimestamp);

    return InclusionProofResponse.certified(
            1L,
            new InclusionProof(certificationData, referenceTime, inclusionCertificate,
                    unicityCertificate));
  }

  /** The answer for a state the aggregator has not certified yet. */
  public static InclusionProofResponse createPendingResponse(DataHash root,
          SigningService signingService, long certificateTimestamp) {
    return InclusionProofResponse.notCertified(1L,
            UnicityCertificateUtils.generateCertificate(signingService, root, certificateTimestamp));
  }

  /** The same proof under another unicity certificate. */
  public static InclusionProof withCertificate(InclusionProof proof,
          UnicityCertificate unicityCertificate) {
    return new InclusionProof(proof.getCertificationData(), proof.getReferenceTime(),
            proof.getInclusionCertificate(), unicityCertificate);
  }
}
