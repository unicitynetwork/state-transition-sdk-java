package org.unicitylabs.sdk.api.bft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;

/**
 * Builds real, signed certificates for tests: a quorum authority of four root nodes, and a sharded
 * partition whose shard tree and unicity tree hash to the seal the authority signed.
 */
public final class TestChain {

  /** The default seal timestamp: after the genesis time. */
  public static final long TIMESTAMP = 1_755_000_000L;

  private TestChain() {
  }

  /** Four root nodes with a threshold of three. */
  public static final class Authority {
    private final SigningService[] signers;
    private final String[] ids;
    private final NetworkId network;
    private final long epoch;
    private final long epochStartRound;

    public Authority(NetworkId network, long epoch, long epochStartRound, int seed) {
      this.network = network;
      this.epoch = epoch;
      this.epochStartRound = epochStartRound;
      this.signers = new SigningService[4];
      this.ids = new String[4];
      for (int i = 0; i < 4; i++) {
        byte[] key = new byte[32];
        key[0] = (byte) seed;
        key[31] = (byte) (i + 1);
        this.signers[i] = new SigningService(key);
        this.ids[i] = "node-" + seed + "-" + i;
      }
    }

    public Authority() {
      this(NetworkId.LOCAL, 1, 1, 1);
    }

    public RootTrustBase trustBase() {
      List<RootTrustBase.NodeInfo> nodes = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        nodes.add(new RootTrustBase.NodeInfo(ids[i], signers[i].getPublicKey(), 1));
      }
      return new RootTrustBase(1, network, epoch, epochStartRound, nodes, 3, new byte[0], null,
          null, Map.of());
    }

    public NetworkId network() {
      return network;
    }

    public long epoch() {
      return epoch;
    }

    /** Signs the seal with the given nodes. */
    public UnicitySeal sign(UnicitySeal unsigned, int... nodes) {
      DataHash hash = new DataHasher(HashAlgorithm.SHA256)
          .update(unsigned.toCborWithoutSignatures()).digest();
      Set<UnicitySeal.SignatureEntry> entries = new LinkedHashSet<>();
      for (int node : nodes) {
        entries.add(new UnicitySeal.SignatureEntry(ids[node], signers[node].sign(hash)));
      }
      return unsigned.withSignatures(entries);
    }

    public UnicitySeal seal(long round, byte[] sealedHash, int... nodes) {
      return sign(new UnicitySeal(network, round, epoch, TIMESTAMP, null, sealedHash, null),
          nodes.length == 0 ? new int[] {0, 1, 2} : nodes);
    }
  }

  /** The certificates of one partition under one seal. */
  public static final class Sealed {
    public final UnicitySeal seal;
    public final PartitionView view;
    public final ShardTree tree;
    private final Map<String, UnicityCertificate> certificates;

    Sealed(UnicitySeal seal, PartitionView view, ShardTree tree,
        Map<String, UnicityCertificate> certificates) {
      this.seal = seal;
      this.view = view;
      this.tree = tree;
      this.certificates = certificates;
    }

    public UnicityCertificate certificate(String shardBits) {
      return certificates.get(shardBits);
    }
  }

  /** A seal without signatures (null map), or with an empty signature map. */
  public static UnicitySeal rawSeal(NetworkId network, long round, long epoch, long timestamp,
      byte[] hash, boolean emptySignatureMap) {
    return new UnicitySeal(network, round, epoch, timestamp, null, hash,
        emptySignatureMap ? new LinkedHashSet<UnicitySeal.SignatureEntry>() : null);
  }

  /** Description of one shard leaf. */
  public static PartitionViewLeaf leaf(String shardBits, long round, long timestamp, int seed) {
    byte[] state = new byte[32];
    state[0] = (byte) seed;
    state[1] = (byte) shardBits.length();
    for (int i = 0; i < shardBits.length(); i++) {
      state[2 + i % 28] ^= (byte) (shardBits.charAt(i) == '1' ? 0x55 : 0x2a);
    }
    InputRecord record = new InputRecord(round, 1, null, state, new byte[8], timestamp,
        new byte[32], 0, null);
    byte[] technical = new byte[32];
    technical[0] = (byte) (seed + 1);
    byte[] configuration = new byte[32];
    configuration[0] = (byte) (seed + 2);
    configuration[1] = (byte) shardBits.length();
    return new PartitionViewLeaf(ShardId.fromBitString(shardBits), record, technical,
        configuration);
  }

  /** Signs the leaves as the partition {@code partition} under a seal of {@code sealRound}. */
  public static Sealed seal(Authority authority, long partition, long sealRound,
      List<PartitionViewLeaf> leaves, int... signers) {
    return seal(authority, partition, sealRound, leaves, Collections.emptyList(), signers);
  }

  /** As {@link #seal} with additional steps of the unicity tree (keys with arbitrary hashes). */
  public static Sealed seal(Authority authority, long partition, long sealRound,
      List<PartitionViewLeaf> leaves, List<UnicityTreeCertificate.HashStep> steps,
      int... signers) {
    ShardTree tree = ShardTree.create(leaves);
    byte[] sealed = unicityRoot(partition, tree.rootHash(), steps);
    UnicitySeal seal = authority.seal(sealRound, sealed, signers);
    PartitionView view = PartitionView.of(partition, leaves);
    UnicityTreeCertificate utc = new UnicityTreeCertificate((int) partition, steps);
    Map<String, UnicityCertificate> certificates = new LinkedHashMap<>();
    for (PartitionViewLeaf leaf : leaves) {
      certificates.put(leaf.getShardId().toString(), new UnicityCertificate(
          leaf.getInputRecord(), leaf.getTechnicalRecordHash(), leaf.getShardConfigurationHash(),
          tree.certificate(leaf.getShardId()), utc, seal));
    }
    return new Sealed(seal, view, tree, certificates);
  }

  /** The hash the seal commits to: the partition leaf folded through the steps. */
  public static byte[] unicityRoot(long partition, byte[] shardRoot,
      List<UnicityTreeCertificate.HashStep> steps) {
    byte[] key = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) partition)
        .array();
    DataHash result = new DataHasher(HashAlgorithm.SHA256)
        .update(CborSerializer.encodeByteString(new byte[] {1}))
        .update(CborSerializer.encodeByteString(key))
        .update(CborSerializer.encodeByteString(new DataHasher(HashAlgorithm.SHA256)
            .update(CborSerializer.encodeByteString(shardRoot)).digest().getData()))
        .digest();
    for (UnicityTreeCertificate.HashStep step : steps) {
      byte[] stepKey = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(step.getKey())
          .array();
      DataHasher hasher = new DataHasher(HashAlgorithm.SHA256)
          .update(CborSerializer.encodeByteString(new byte[] {0}))
          .update(CborSerializer.encodeByteString(stepKey));
      if (java.util.Arrays.compareUnsigned(key, stepKey) > 0) {
        hasher.update(CborSerializer.encodeByteString(step.getHash()))
            .update(CborSerializer.encodeByteString(result.getData()));
      } else {
        hasher.update(CborSerializer.encodeByteString(result.getData()))
            .update(CborSerializer.encodeByteString(step.getHash()));
      }
      result = hasher.digest();
    }
    return result.getData();
  }

  /** A step of the unicity tree. */
  public static UnicityTreeCertificate.HashStep step(int key, int fill) {
    byte[] hash = new byte[32];
    java.util.Arrays.fill(hash, (byte) fill);
    return new UnicityTreeCertificate.HashStep(key, hash);
  }

  /** The four shards 00, 01, 10 and 11. */
  public static List<PartitionViewLeaf> fourShards(long round, long timestamp) {
    return List.of(leaf("00", round, timestamp, 1), leaf("01", round, timestamp, 2),
        leaf("10", round, timestamp, 3), leaf("11", round, timestamp, 4));
  }
}
