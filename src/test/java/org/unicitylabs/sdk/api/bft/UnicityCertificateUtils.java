package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

public class UnicityCertificateUtils {

  public static UnicityCertificate generateCertificate(
          SigningService signingService,
          DataHash rootHash
  ) {
    InputRecord inputRecord = new InputRecord(
            0,
            0,
            null,
            rootHash.getData(),
            new byte[10],
            0,
            new byte[10],
            0,
            new byte[10]
    );
    UnicityTreeCertificate unicityTreeCertificate = new UnicityTreeCertificate(0, List.of());
    byte[] technicalRecordHash = new byte[32];
    byte[] shardConfigurationHash = new byte[32];
    ShardTreeCertificate shardTreeCertificate = new ShardTreeCertificate(
            ShardId.decode(new byte[]{(byte) 0b10000000}), List.of()
    );

    DataHash shardTreeCertificateRootHash = UnicityCertificate.calculateShardTreeCertificateRootHash(
            inputRecord,
            technicalRecordHash,
            shardConfigurationHash,
            shardTreeCertificate
    );

    byte[] key = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(unicityTreeCertificate.getPartitionIdentifier())
            .array();

    DataHash unicitySealHash = new DataHasher(HashAlgorithm.SHA256)
            .update(CborSerializer.encodeByteString(new byte[]{(byte) 0x01})) // LEAF
            .update(CborSerializer.encodeByteString(key))
            .update(
                    CborSerializer.encodeByteString(
                            new DataHasher(HashAlgorithm.SHA256)
                                    .update(
                                            CborSerializer.encodeByteString(
                                                    shardTreeCertificateRootHash.getData()
                                            )
                                    )
                                    .digest()
                                    .getData()
                    )
            )
            .digest();

    UnicitySeal seal = new UnicitySeal(
            (short) 0,
            0L,
            0L,
            0L,
            null,
            unicitySealHash.getData(),
            null
    );

    return new UnicityCertificate(
            new InputRecord(0, 0, null, rootHash.getData(), new byte[10], 0,
                    new byte[10], 0, new byte[10]),
            technicalRecordHash,
            shardConfigurationHash,
            shardTreeCertificate,
            new UnicityTreeCertificate(0, List.of()),
            seal.withSignatures(
                    Map.of(
                            "NODE",
                            signingService.sign(
                                    new DataHasher(HashAlgorithm.SHA256).update(seal.toCbor()).digest()
                            ).encode()
                    )
            )
    );
  }
}
