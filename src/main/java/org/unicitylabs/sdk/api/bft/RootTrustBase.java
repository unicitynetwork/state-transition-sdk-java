package org.unicitylabs.sdk.api.bft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.serializer.json.JsonSerializationException;
import org.unicitylabs.sdk.serializer.json.UnsignedLongJson;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.UnsignedLongs;

/**
 * Root trust base information.
 */
public class RootTrustBase {

  private static final long VERSION = 1;

  private final NetworkId networkId;
  private final long epoch;
  private final long epochStartRound;
  private final Map<String, NodeInfo> rootNodes;
  private final long quorumThreshold;
  private final byte[] stateHash;
  private final byte[] changeRecordHash;
  private final byte[] previousEntryHash;
  private final Map<String, byte[]> signatures;

  @JsonCreator
  RootTrustBase(
          @JsonProperty("version")
          @JsonDeserialize(using = UnsignedLongJson.Deserializer.class) long version,
          @JsonProperty("networkId") NetworkId networkId,
          @JsonProperty("epoch")
          @JsonDeserialize(using = UnsignedLongJson.Deserializer.class) long epoch,
          @JsonProperty("epochStartRound")
          @JsonDeserialize(using = UnsignedLongJson.Deserializer.class) long epochStartRound,
          @JsonProperty("rootNodes") List<NodeInfo> rootNodes,
          @JsonProperty("quorumThreshold")
          @JsonDeserialize(using = UnsignedLongJson.Deserializer.class) long quorumThreshold,
          @JsonProperty("stateHash") byte[] stateHash,
          @JsonProperty("changeRecordHash") byte[] changeRecordHash,
          @JsonProperty("previousEntryHash") byte[] previousEntryHash,
          @JsonProperty("signatures") Map<String, byte[]> signatures
  ) {
    if (version != RootTrustBase.VERSION) {
      throw new IllegalArgumentException(
              String.format("Unsupported RootTrustBase version: %s", UnsignedLongs.toDecimal(version)));
    }

    Objects.requireNonNull(rootNodes, "rootNodes cannot be null");

    Map<String, NodeInfo> nodes = new LinkedHashMap<>();
    Set<String> signingKeys = new HashSet<>();
    for (NodeInfo node : rootNodes) {
      Objects.requireNonNull(node, "Trust base node cannot be null");
      if (nodes.putIfAbsent(node.getNodeId(), node) != null) {
        throw new IllegalArgumentException(
                String.format("Duplicate trust base node id: %s", node.getNodeId()));
      }
      if (!signingKeys.add(HexConverter.encode(node.getSigningKey()))) {
        throw new IllegalArgumentException("Duplicate trust base signing key.");
      }
    }

    if (nodes.isEmpty()) {
      throw new IllegalArgumentException("Trust base must contain at least one root node.");
    }

    if (quorumThreshold == 0 || UnsignedLongs.compare(quorumThreshold, nodes.size()) > 0) {
      throw new IllegalArgumentException(
              "Trust base quorum threshold must be between 1 and the root node count.");
    }

    this.networkId = networkId;
    this.epoch = epoch;
    this.epochStartRound = epochStartRound;
    this.rootNodes = nodes;
    this.quorumThreshold = quorumThreshold;
    this.stateHash = Arrays.copyOf(stateHash, stateHash.length);
    this.changeRecordHash = changeRecordHash == null
            ? null
            : Arrays.copyOf(changeRecordHash, changeRecordHash.length);
    this.previousEntryHash = previousEntryHash == null
            ? null
            : Arrays.copyOf(previousEntryHash, previousEntryHash.length);
    this.signatures = signatures.entrySet().stream()
            .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> Arrays.copyOf(e.getValue(), e.getValue().length)
            ));
  }

  /**
   * Get version.
   *
   * @return version
   */
  @JsonSerialize(using = UnsignedLongJson.Serializer.class)
  public long getVersion() {
    return RootTrustBase.VERSION;
  }

  /**
   * Get network id.
   *
   * @return network id
   */
  public NetworkId getNetworkId() {
    return this.networkId;
  }

  /**
   * Get current epoch as unsigned 64-bit bits (2^63 or above reads as a negative {@code long}).
   * See {@link #getEpochUnsigned()}.
   *
   * @return epoch bits
   */
  @JsonSerialize(using = UnsignedLongJson.Serializer.class)
  public long getEpoch() {
    return this.epoch;
  }

  /**
   * Get epoch start round as unsigned 64-bit bits. See {@link #getEpochStartRoundUnsigned()}.
   *
   * @return epoch start round bits
   */
  @JsonSerialize(using = UnsignedLongJson.Serializer.class)
  public long getEpochStartRound() {
    return this.epochStartRound;
  }

  /**
   * Get current epoch as a non-negative integer.
   *
   * @return epoch
   */
  @JsonIgnore
  public BigInteger getEpochUnsigned() {
    return UnsignedLongs.toBigInteger(this.epoch);
  }

  /**
   * Get epoch start round as a non-negative integer.
   *
   * @return epoch start round
   */
  @JsonIgnore
  public BigInteger getEpochStartRoundUnsigned() {
    return UnsignedLongs.toBigInteger(this.epochStartRound);
  }

  /**
   * Get root nodes.
   *
   * @return root nodes
   */
  public Set<NodeInfo> getRootNodes() {
    return Set.copyOf(this.rootNodes.values());
  }

  /**
   * Get root node by node id.
   *
   * @param nodeId node id
   * @return node info, or {@code null} when no node with the given id exists
   */
  public NodeInfo getRootNode(String nodeId) {
    return this.rootNodes.get(nodeId);
  }

  /**
   * Get quorum threshold.
   *
   * @return quorum threshold
   */
  @JsonSerialize(using = UnsignedLongJson.Serializer.class)
  public long getQuorumThreshold() {
    return this.quorumThreshold;
  }

  /**
   * Get state hash.
   *
   * @return state hash
   */
  public byte[] getStateHash() {
    return Arrays.copyOf(this.stateHash, this.stateHash.length);
  }

  /**
   * Get change record hash.
   *
   * @return change record hash
   */
  public byte[] getChangeRecordHash() {
    return this.changeRecordHash == null
            ? null
            : Arrays.copyOf(this.changeRecordHash, this.changeRecordHash.length);
  }

  /**
   * Get previous entry hash.
   *
   * @return previous entry hash
   */
  public byte[] getPreviousEntryHash() {
    return this.previousEntryHash == null
            ? null
            : Arrays.copyOf(this.previousEntryHash, this.previousEntryHash.length);
  }

  /**
   * Get signatures.
   *
   * @return signatures
   */
  public Map<String, byte[]> getSignatures() {
    return Map.copyOf(
            this.signatures.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> Arrays.copyOf(e.getValue(), e.getValue().length)
                    ))
    );
  }

  /**
   * Create a root trust base from JSON string.
   *
   * @param input JSON string
   * @return root trust base
   */
  public static RootTrustBase fromJson(String input) {
    try {
      return UnicityObjectMapper.JSON.readValue(input, RootTrustBase.class);
    } catch (JsonProcessingException e) {
      throw new JsonSerializationException(RootTrustBase.class, e);
    }
  }

  /**
   * Convert root trust base to JSON string.
   *
   * @return JSON string
   */
  public String toJson() {
    try {
      return UnicityObjectMapper.JSON.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new JsonSerializationException(RootTrustBase.class, e);
    }
  }

  /**
   * Node information.
   */
  public static class NodeInfo {

    private final String nodeId;
    private final byte[] signingKey;
    private final long stakedAmount;

    @JsonCreator
    NodeInfo(
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("sigKey") byte[] signingKey,
            @JsonProperty("stake")
            @JsonDeserialize(using = UnsignedLongJson.Deserializer.class) long stakedAmount
    ) {
      Objects.requireNonNull(nodeId, "Node id cannot be null");
      Objects.requireNonNull(signingKey, "Node signing key cannot be null");
      if (stakedAmount == 0) {
        throw new IllegalArgumentException("Each trust base root node must have positive stake.");
      }

      this.nodeId = nodeId;
      this.signingKey = Arrays.copyOf(signingKey, signingKey.length);
      this.stakedAmount = stakedAmount;
    }

    /**
     * Get node ID.
     *
     * @return node ID
     */
    public String getNodeId() {
      return this.nodeId;
    }

    /**
     * Get signing key.
     *
     * @return signing key
     */
    @JsonProperty("sigKey")
    public byte[] getSigningKey() {
      return Arrays.copyOf(this.signingKey, this.signingKey.length);
    }

    /**
     * Get the staked amount as unsigned 64-bit bits. See {@link #getStakedAmountUnsigned()}.
     *
     * @return staked amount bits
     */
    @JsonProperty("stake")
    @JsonSerialize(using = UnsignedLongJson.Serializer.class)
    public long getStakedAmount() {
      return this.stakedAmount;
    }

    /**
     * Get the staked amount as a non-negative integer.
     *
     * @return staked amount
     */
    @JsonIgnore
    public BigInteger getStakedAmountUnsigned() {
      return UnsignedLongs.toBigInteger(this.stakedAmount);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof NodeInfo)) {
        return false;
      }
      NodeInfo nodeInfo = (NodeInfo) o;
      return Objects.equals(this.nodeId, nodeInfo.nodeId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(nodeId);
    }
  }
}