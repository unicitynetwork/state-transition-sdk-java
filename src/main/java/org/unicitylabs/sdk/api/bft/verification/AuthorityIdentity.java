package org.unicitylabs.sdk.api.bft.verification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Canonical identity of everything a seal verdict depends on in a trust base: network, epoch,
 * epoch start round, quorum threshold and every root node (id, signing key, stake). Two trust
 * bases with equal identities judge every seal alike. A trust base is immutable, so the identity is
 * built once per session.
 */
final class AuthorityIdentity {

  private AuthorityIdentity() {
  }

  static String of(RootTrustBase trustBase) {
    List<RootTrustBase.NodeInfo> nodes = new ArrayList<>(trustBase.getRootNodes());
    nodes.sort(Comparator.comparing(RootTrustBase.NodeInfo::getNodeId));
    byte[][] encodedNodes = new byte[nodes.size()][];
    for (int i = 0; i < encodedNodes.length; i++) {
      RootTrustBase.NodeInfo node = nodes.get(i);
      encodedNodes[i] = CborSerializer.encodeArray(
              CborSerializer.encodeTextString(node.getNodeId()),
              CborSerializer.encodeByteString(node.getSigningKey()),
              CborSerializer.encodeUnsignedInteger(node.getStakedAmount())
      );
    }
    byte[] encoded = CborSerializer.encodeArray(
            CborSerializer.encodeTextString("unicity-sdk/authority/v1"),
            CborSerializer.encodeUnsignedInteger(trustBase.getVersion()),
            CborSerializer.encodeUnsignedInteger(trustBase.getNetworkId().getId()),
            CborSerializer.encodeUnsignedInteger(trustBase.getEpoch()),
            CborSerializer.encodeUnsignedInteger(trustBase.getEpochStartRound()),
            CborSerializer.encodeUnsignedInteger(trustBase.getQuorumThreshold()),
            CborSerializer.encodeArray(encodedNodes)
    );
    return "authority:" + HexConverter.encode(
            new DataHasher(HashAlgorithm.SHA256).update(encoded).digest().getData());
  }
}
