package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.api.bft.UnicityCertificateUtils;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;

public class InclusionProofFixture {
    public static InclusionProofResponse createResponse(CertificationData certificationData, InclusionCertificate inclusionCertificate, DataHash root, SigningService signingService) {
        return new InclusionProofResponse(
                1L,
                new InclusionProof(
                        certificationData,
                        inclusionCertificate,
                        UnicityCertificateUtils.generateCertificate(signingService, root)
                )
        );
    }
}
