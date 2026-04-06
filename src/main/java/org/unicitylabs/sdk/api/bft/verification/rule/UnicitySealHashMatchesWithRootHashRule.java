package org.unicitylabs.sdk.api.bft.verification.rule;

import com.google.common.primitives.UnsignedBytes;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.api.bft.UnicityTreeCertificate;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Rule to verify that the UnicitySeal hash matches the root hash of the UnicityTreeCertificate.
 */
public class UnicitySealHashMatchesWithRootHashRule {

  private UnicitySealHashMatchesWithRootHashRule() {}

  /**
   * Verifies that the unicity seal hash matches the recomputed root hash of the unicity tree.
   *
   * @param unicityCertificate unicity certificate containing tree and seal data
   * @return verification result with {@link VerificationStatus#OK} on match, otherwise fail
   */
  public static VerificationResult<VerificationStatus> verify(
      UnicityCertificate unicityCertificate) {
    DataHash shardTreeCertificateRootHash = UnicityCertificate
        .calculateShardTreeCertificateRootHash(
            unicityCertificate.getInputRecord(),
            unicityCertificate.getTechnicalRecordHash(),
            unicityCertificate.getShardConfigurationHash(),
            unicityCertificate.getShardTreeCertificate()
        );

    UnicityTreeCertificate unicityTreeCertificate = unicityCertificate.getUnicityTreeCertificate();
    byte[] key = ByteBuffer.allocate(4)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(unicityTreeCertificate.getPartitionIdentifier())
        .array();

    DataHash result = new DataHasher(HashAlgorithm.SHA256)
        .update(CborSerializer.encodeByteString(new byte[]{(byte) 0x01})) // LEAF
        .update(CborSerializer.encodeByteString(key))
        .update(
            CborSerializer.encodeByteString(
                new DataHasher(HashAlgorithm.SHA256)
                    .update(
                        CborSerializer.encodeByteString(shardTreeCertificateRootHash.getData())
                    )
                    .digest()
                    .getData()
            )
        )
        .digest();

    for (UnicityTreeCertificate.HashStep step : unicityTreeCertificate.getSteps()) {
      byte[] stepKey = ByteBuffer.allocate(4)
          .order(ByteOrder.BIG_ENDIAN)
          .putInt(step.getKey())
          .array();

      DataHasher hasher = new DataHasher(HashAlgorithm.SHA256)
          .update(CborSerializer.encodeByteString(new byte[]{(byte) 0x00})) // NODE
          .update(CborSerializer.encodeByteString(stepKey));

      if (UnsignedBytes.lexicographicalComparator().compare(key, stepKey) > 0) {
        hasher
            .update(CborSerializer.encodeByteString(step.getHash()))
            .update(CborSerializer.encodeByteString(result.getData()));
      } else {
        hasher
            .update(CborSerializer.encodeByteString(result.getData()))
            .update(CborSerializer.encodeByteString(step.getHash()));
      }

      result = hasher.digest();
    }

    byte[] unicitySealHash = unicityCertificate.getUnicitySeal().getHash();

    if (!Arrays.equals(unicitySealHash, result.getData())) {
      return new VerificationResult<>("UnicitySealHashMatchesWithRootHashRule",
          VerificationStatus.FAIL);
    }

    return new VerificationResult<>("UnicitySealHashMatchesWithRootHashRule",
        VerificationStatus.OK);
  }
}
