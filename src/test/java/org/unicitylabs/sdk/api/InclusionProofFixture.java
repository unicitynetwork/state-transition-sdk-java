package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.api.bft.UnicityCertificateUtils;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTreePath;

public class InclusionProofFixture {
    public static InclusionProofResponse create(SparseMerkleTreePath path, CertificationData certificationData, DataHash root, SigningService signingService) {
        return new InclusionProofResponse(
                1L,
                new InclusionProof(
                        path,
                        certificationData,
                        UnicityCertificateUtils.generateCertificate(signingService, root)
                )
        );
    }
}
