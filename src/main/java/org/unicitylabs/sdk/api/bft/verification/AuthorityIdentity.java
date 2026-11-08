package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Canonical identities of what a seal verdict depends on.
 *
 * <p>The identity of one entry is its entry id: SHA-256 of the entry without its signatures, which
 * commits to the network, the epoch, the start round, the threshold and every member with its key
 * and weight. Two entries with the same id judge every seal alike. The identity of a view is its
 * pinned genesis and its held tip: it changes when the history grows, which costs a cache miss,
 * never a wrong hit.
 */
final class AuthorityIdentity {

  private AuthorityIdentity() {
  }

  static String ofEntry(TrustBaseEntry entry) {
    return "entry:" + HexConverter.encode(entry.entryId());
  }

  static String ofView(TrustBaseHistoryView view) {
    byte[] tip = view.getTip().map(TrustBaseEntry::entryId).orElse(new byte[0]);
    byte[] encoded = CborSerializer.encodeArray(
        CborSerializer.encodeTextString("unicity-sdk/authority/v2"),
        CborSerializer.encodeByteString(view.getGenesisPin()),
        CborSerializer.encodeByteString(tip));
    return "authority:" + HexConverter.encode(
        new DataHasher(HashAlgorithm.SHA256).update(encoded).digest().getData());
  }
}
