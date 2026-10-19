package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class UnicityCertificateUtils {

  /**
   * Reference time the fixtures pin a certified leaf to.
   *
   * <p>A real service sets the round's input record timestamp to the very reference time its
   * leaves are built from, so a fixture certificate defaults to certifying a round with this
   * clock. Pairing a leaf with a round whose timestamp precedes it is not something any aggregator
   * can produce, and the verification rule rejects it.
   */
  public static final long REFERENCE_TIME = 1755000000L;

  public static UnicityCertificate generateCertificate(
          SigningService signingService,
          DataHash rootHash
  ) {
    return generateCertificate(signingService, rootHash, REFERENCE_TIME);
  }

  public static UnicityCertificate generateCertificate(
          SigningService signingService,
          DataHash rootHash,
          long timestamp
  ) {
    return generateCertificate(signingService, rootHash,
            ShardId.decode(new byte[]{(byte) 0b10000000}), timestamp);
  }

  public static UnicityCertificate generateCertificate(
          SigningService signingService,
          DataHash rootHash,
          ShardId shardId
  ) {
    return generateCertificate(signingService, rootHash, shardId, REFERENCE_TIME);
  }

  public static UnicityCertificate generateCertificate(
          SigningService signingService,
          DataHash rootHash,
          ShardId shardId,
          long timestamp
  ) {
    InputRecord inputRecord = new InputRecord(
            0,
            0,
            null,
            rootHash.getData(),
            new byte[10],
            timestamp,
            new byte[10],
            0,
            new byte[10]
    );
    UnicityTreeCertificate unicityTreeCertificate = new UnicityTreeCertificate(7, List.of());
    byte[] technicalRecordHash = new byte[32];
    byte[] shardConfigurationHash = new byte[32];
    // a shard path has exactly one sibling per bit of the shard id
    List<byte[]> siblings = new ArrayList<>();
    for (int i = 0; i < shardId.getLength(); i++) {
      siblings.add(new byte[32]);
    }
    ShardTreeCertificate shardTreeCertificate = new ShardTreeCertificate(shardId, siblings);

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
            NetworkId.LOCAL,
            1L,
            0L,
            timestamp,
            null,
            unicitySealHash.getData(),
            null
    );

    return new UnicityCertificate(
            new InputRecord(0, 0, null, rootHash.getData(), new byte[10], timestamp,
                    new byte[10], 0, new byte[10]),
            technicalRecordHash,
            shardConfigurationHash,
            shardTreeCertificate,
            new UnicityTreeCertificate(7, List.of()),
            seal.withSignatures(
                    Set.of(
                            new UnicitySeal.SignatureEntry(
                                    "NODE",
                                    signingService.sign(
                                            new DataHasher(HashAlgorithm.SHA256).update(seal.toCbor()).digest()
                                    )
                            )
                    )
            )
    );
  }
}
