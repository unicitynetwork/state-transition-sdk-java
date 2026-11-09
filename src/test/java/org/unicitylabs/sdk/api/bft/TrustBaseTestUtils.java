package org.unicitylabs.sdk.api.bft;

import java.util.List;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseBundle;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseBundleLimits;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistory;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseMember;

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
    members = new java.util.ArrayList<>(members);
    members.sort(java.util.Comparator.comparing(
        m -> m.getNodeId().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        java.util.Arrays::compareUnsigned));
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
   * The history of a trust base bundle (a corpus authority: tag 39015, genesis to tip), pinned by the
   * identity of its own genesis entry the way a verifier opens a local deployment's bundle.
   */
  public static TrustBaseHistory fromBundle(byte[] bytes) {
    TrustBaseBundle bundle = TrustBaseBundle.decode(bytes,
        new TrustBaseBundleLimits(bytes.length, 1024));
    return TrustBaseHistory.fromBundle(bundle, bundle.getEntries().get(0).entryId());
  }

  /**
   * The complete trust base bundle (tag 39015, genesis to tip) a running aggregator serves at
   * {@code GET /api/v1/trustbases}. For a deployment this suite started itself, whose bundle is its
   * own authority (the local-network setup); never a way to authenticate a network.
   */
  public static byte[] fetchServedBundle(String aggregatorUrl) throws java.io.IOException {
    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
        java.net.URI.create(aggregatorUrl.replaceAll("/+$", "")
            + "/api/v1/trustbases?from=1&maxEntries=64")).GET().build();
    try {
      java.net.http.HttpResponse<byte[]> response = java.net.http.HttpClient.newHttpClient()
          .send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        throw new java.io.IOException("the aggregator serves no trust base bundle: HTTP "
            + response.statusCode());
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new java.io.IOException(e);
    }
  }

  /**
   * Open the bundle of a local deployment as a session, the way an application opens its own
   * network, and return the session's history. The bundle is the deployment's authority; this is
   * never a way to authenticate a public network.
   */
  public static org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView openLocal(
      byte[] bundle) throws java.io.IOException {
    try {
      return org.unicitylabs.sdk.api.bft.trustbase.TrustBaseSession.openLocal(bundle,
          new org.unicitylabs.sdk.api.bft.trustbase.MemoryTrustBaseStore(),
          org.unicitylabs.sdk.api.bft.trustbase.TrustBaseSessionOptions.of(Runnable::run))
          .get().getSession().history();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new java.io.IOException(e);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new java.io.IOException("the bundle does not open as a local session", e);
    }
  }
}
