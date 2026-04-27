package org.unicitylabs.sdk.api;

import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/**
 * Submit certification request.
 */
public class CertificationRequest {
    public static final long CBOR_TAG = 39030;
    private static final int VERSION = 1;

    private final StateId stateId;
    private final CertificationData certificationData;

    /**
     * Create certification request.
     *
     * @param stateId           state id
     * @param certificationData transaction hash
     */
    private CertificationRequest(
            StateId stateId,
            CertificationData certificationData
    ) {
        this.stateId = stateId;
        this.certificationData = certificationData;
    }

    public int getVersion() {
        return CertificationRequest.VERSION;
    }

    /**
     * Get state id.
     *
     * @return state id
     */
    public StateId getStateId() {
        return this.stateId;
    }

    /**
     * Get certification data.
     *
     * @return certification data
     */
    public CertificationData getCertificationData() {
        return this.certificationData;
    }

    /**
     * Create certification request.
     *
     * @param certificationData certification data
     * @return certification request
     */
    public static CertificationRequest create(CertificationData certificationData) {
        return new CertificationRequest(StateId.fromCertificationData(certificationData),
                certificationData);
    }

    /**
     * Convert the request to a CBOR bytes.
     *
     * @return CBOR bytes
     */
    public byte[] toCBOR() {
        return CborSerializer.encodeTag(
                CertificationRequest.CBOR_TAG,
                CborSerializer.encodeArray(
                        CborSerializer.encodeUnsignedInteger(CertificationRequest.VERSION),
                        this.stateId.toCbor(),
                        this.certificationData.toCbor(),
                        CborSerializer.encodeUnsignedInteger(0)
                )
        );
    }
}
