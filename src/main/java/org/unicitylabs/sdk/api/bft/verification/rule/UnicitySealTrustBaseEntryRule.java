package org.unicitylabs.sdk.api.bft.verification.rule;

import java.util.Optional;
import java.util.OptionalLong;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.api.bft.verification.NoTrustBaseEntryResult;
import org.unicitylabs.sdk.api.bft.verification.TrustBaseEntryResult;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateFailure;
import org.unicitylabs.sdk.util.UnsignedLongs;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Rule that picks the trust-base entry of the seal's epoch and checks the seal belongs to it: the
 * same network, and a root chain round inside the epoch's interval (from the entry's start round,
 * up to, but excluding, the start round of the held successor, if there is one).
 */
public final class UnicitySealTrustBaseEntryRule {

  private UnicitySealTrustBaseEntryRule() {
  }

  /**
   * Place a seal in the history.
   *
   * @param view held trust-base history
   * @param seal seal to place
   * @return the entry on success; a {@link NoTrustBaseEntryResult} when the epoch is above the
   *     held tip or the history is empty; an ordinary failure for another network, an epoch
   *     before the genesis or a round outside the interval of the epoch
   */
  public static TrustBaseEntryResult verify(TrustBaseHistoryView view, UnicitySeal seal) {
    // Network first: a seal of another network is no business of any fetch, whatever its epoch.
    Optional<NetworkId> network = view.getNetworkId();
    if (network.isPresent() && !network.get().equals(seal.getNetworkId())) {
      return new TrustBaseEntryResult(VerificationStatus.FAIL,
          "The seal is of another network than the history.", null,
          UnicityCertificateFailure.NETWORK_MISMATCH);
    }

    Optional<TrustBaseEntry> found = view.entryForEpoch(seal.getEpoch());
    if (!found.isPresent()) {
      Optional<TrustBaseEntry> tip = view.getTip();
      // Only an epoch above the held tip (or an empty history) can be supplied by a later fetch.
      // An epoch before the genesis can never be, so it is an ordinary failure.
      if (!tip.isPresent() || UnsignedLongs.compare(seal.getEpoch(), tip.get().getEpoch()) > 0) {
        return new NoTrustBaseEntryResult(seal.getEpoch(),
            tip.isPresent() ? tip.get().getEpoch() : null);
      }
      return new TrustBaseEntryResult(VerificationStatus.FAIL, "Epoch "
          + UnsignedLongs.toDecimal(seal.getEpoch()) + " is before the genesis epoch of the history.",
          null, UnicityCertificateFailure.EPOCH_MISMATCH);
    }

    TrustBaseEntry entry = found.get();
    long round = seal.getRootChainRoundNumber();
    if (UnsignedLongs.lessThan(round, entry.getEpochStartRound())) {
      return new TrustBaseEntryResult(VerificationStatus.FAIL, "Round "
          + UnsignedLongs.toDecimal(round) + " is before the start "
          + UnsignedLongs.toDecimal(entry.getEpochStartRound()) + " of epoch "
          + UnsignedLongs.toDecimal(entry.getEpoch()) + ".", null,
          UnicityCertificateFailure.BEFORE_EPOCH_START);
    }
    OptionalLong end = view.epochEndRound(entry.getEpoch());
    if (end.isPresent() && !UnsignedLongs.lessThan(round, end.getAsLong())) {
      return new TrustBaseEntryResult(VerificationStatus.FAIL, "Round "
          + UnsignedLongs.toDecimal(round) + " is at or after the start "
          + UnsignedLongs.toDecimal(end.getAsLong()) + " of epoch "
          + UnsignedLongs.toDecimal(entry.getEpoch() + 1) + ".", null,
          UnicityCertificateFailure.AFTER_EPOCH_END);
    }
    return new TrustBaseEntryResult(VerificationStatus.OK, "", entry, null);
  }
}
