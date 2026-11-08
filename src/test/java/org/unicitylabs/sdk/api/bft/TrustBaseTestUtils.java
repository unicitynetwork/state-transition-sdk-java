package org.unicitylabs.sdk.api.bft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistory;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseMember;
import org.unicitylabs.sdk.util.HexConverter;

/** Test trust bases: a history holding one genesis entry, pinned by its own entry id. */
public final class TrustBaseTestUtils {

  private TrustBaseTestUtils() {
  }

  /** A history of one single-member genesis entry: network LOCAL, epoch 1, start round 1. */
  public static TrustBaseHistory singleNode(byte[] publicKey) {
    return singleNode(NetworkId.LOCAL, "NODE", publicKey, 1, 1);
  }

  /** A history of one single-member genesis entry. */
  public static TrustBaseHistory singleNode(NetworkId network, String nodeId, byte[] publicKey,
      long epoch, long epochStartRound) {
    return genesis(network, epoch, epochStartRound,
        List.of(new TrustBaseMember(nodeId, publicKey, 1)));
  }

  /** A history of one genesis entry of the given members, with the exact threshold. */
  public static TrustBaseHistory genesis(NetworkId network, long epoch, long epochStartRound,
      List<TrustBaseMember> members) {
    long total = 0;
    for (TrustBaseMember member : members) {
      total += member.getWeight();
    }
    TrustBaseEntry entry = new TrustBaseEntry(network.getId(), epoch, epochStartRound, members,
        total * 2 / 3 + 1, null, null, new byte[32], null);
    TrustBaseHistory history = TrustBaseHistory.create(entry.entryId());
    history.append(entry);
    return history;
  }

  /**
   * A history of one genesis entry from the recorded fields of a V1 JSON trust base (network,
   * epoch, start round, nodes with key and stake): the corpus fixtures record real seals and the
   * authority that signed them in that form. The threshold is the exact one for the weights.
   */
  public static TrustBaseHistory fromLegacyJson(String json) {
    try {
      JsonNode node = new ObjectMapper().readTree(json);
      List<TrustBaseMember> members = new ArrayList<>();
      for (JsonNode n : node.get("rootNodes")) {
        members.add(new TrustBaseMember(n.get("nodeId").asText(),
            HexConverter.decode(n.get("sigKey").asText()), n.get("stake").asLong()));
      }
      members.sort(Comparator.comparing(m -> m.getNodeId().getBytes(StandardCharsets.UTF_8),
          java.util.Arrays::compareUnsigned));
      return genesis(NetworkId.fromId((short) node.get("networkId").asInt()),
          Long.parseUnsignedLong(node.get("epoch").asText()) == 0
              ? 1 : Long.parseUnsignedLong(node.get("epoch").asText()),
          Long.parseUnsignedLong(node.get("epochStartRound").asText()), members);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
