package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.util.UnsignedLongs;

/**
 * The append-only Unicity Trust Base history a verifier holds: consecutive entries from a genesis
 * entry pinned out of band by its entry id, each authenticated by a strict quorum of its
 * predecessor's members. The reference is the shared vectors' history model (bft-go-base
 * {@code types/root_trust_base_v2_vectors_test.go}) and bft-core {@code utbstore}.
 *
 * <p>{@link #append(TrustBaseEntry)} is the only way in, for the genesis and for every later
 * entry, including those of a loaded bundle. An entry of a held epoch changes nothing: the same
 * entry id is {@link TrustBaseAppendOutcome#ALREADY_HELD} (its signature set must still verify, so
 * a bad witness is an error), a different one is a conflict. An entry more than one epoch ahead is
 * a gap. Appends are serialised and every reader sees a consistent chain; no I/O happens under the
 * lock. Epochs and rounds are unsigned 64-bit numbers carried as {@code long} bits.
 */
public final class TrustBaseHistory implements TrustBaseHistoryView {

  private final Object lock = new Object();
  private final List<TrustBaseEntry> entries = new ArrayList<>();
  private final byte[] genesisPin;

  private TrustBaseHistory(byte[] genesisPin) {
    this.genesisPin = genesisPin;
  }

  /**
   * An empty history bound to the genesis it will accept.
   *
   * @param genesisPin entry id of the genesis entry, from outside the history (a release pin);
   *     copied
   * @return empty history
   * @throws TrustBaseInvalidException if the pin is not a 32-byte entry id
   */
  public static TrustBaseHistory create(byte[] genesisPin) {
    Objects.requireNonNull(genesisPin, "genesisPin cannot be null");
    if (genesisPin.length != TrustBaseEntry.ID_LENGTH) {
      throw new TrustBaseInvalidException("Genesis pin of " + genesisPin.length + " bytes.");
    }
    return new TrustBaseHistory(Arrays.copyOf(genesisPin, genesisPin.length));
  }

  /**
   * Load a complete bundle through {@link #append}, like every later entry.
   *
   * @param bundle decoded bundle
   * @param genesisPin entry id of the genesis entry
   * @return history holding every entry of the bundle
   * @throws TrustBaseChainException if the genesis is not the pinned one or an entry does not
   *     extend its predecessor
   * @throws TrustBaseSignatureException if an entry is not signed by a quorum of its predecessor
   */
  public static TrustBaseHistory fromBundle(TrustBaseBundle bundle, byte[] genesisPin) {
    TrustBaseHistory history = create(genesisPin);
    for (TrustBaseEntry entry : bundle.getEntries()) {
      history.append(entry);
    }
    return history;
  }

  /**
   * Get a snapshot of the held entries.
   *
   * @return immutable list, ascending from the genesis
   */
  @Override
  public List<TrustBaseEntry> getEntries() {
    synchronized (this.lock) {
      return List.copyOf(this.entries);
    }
  }

  /**
   * Get the pinned entry id of the genesis.
   *
   * @return copy
   */
  @Override
  public byte[] getGenesisPin() {
    return Arrays.copyOf(this.genesisPin, this.genesisPin.length);
  }

  /**
   * Tell whether no entry is held yet.
   *
   * @return true if empty
   */
  public boolean isEmpty() {
    synchronized (this.lock) {
      return this.entries.isEmpty();
    }
  }

  /**
   * Get the network of the held entries.
   *
   * @return the network, empty when the history is empty
   */
  @Override
  public Optional<NetworkId> getNetworkId() {
    synchronized (this.lock) {
      return this.entries.isEmpty() ? Optional.empty()
          : Optional.of(this.entries.get(0).getNetworkId());
    }
  }

  /**
   * Get the latest held entry.
   *
   * @return the tip, empty when the history is empty
   */
  @Override
  public Optional<TrustBaseEntry> getTip() {
    synchronized (this.lock) {
      return this.entries.isEmpty() ? Optional.empty()
          : Optional.of(this.entries.get(this.entries.size() - 1));
    }
  }

  /**
   * Add an entry.
   *
   * @param entry decoded entry
   * @return {@link TrustBaseAppendOutcome#APPENDED}, or
   *     {@link TrustBaseAppendOutcome#ALREADY_HELD} when the entry is already held
   * @throws TrustBaseInvalidException if the first entry is not a genesis entry
   * @throws TrustBaseChainException if the first entry is not the pinned genesis, or the entry does
   *     not extend the tip (or its held predecessor)
   * @throws TrustBaseSignatureException if the predecessor's members did not sign it with a quorum
   * @throws TrustBaseConflictException if a different entry is held for the epoch
   * @throws TrustBaseGapException if the entry is more than one epoch ahead of the tip
   */
  public TrustBaseAppendOutcome append(TrustBaseEntry entry) {
    Objects.requireNonNull(entry, "entry cannot be null");
    synchronized (this.lock) {
      if (this.entries.isEmpty()) {
        entry.verifyGenesis();
        if (!Arrays.equals(entry.entryId(), this.genesisPin)) {
          throw new TrustBaseChainException("The genesis is not the pinned identity.");
        }
        this.entries.add(entry);
        return TrustBaseAppendOutcome.APPENDED;
      }

      TrustBaseEntry first = this.entries.get(0);
      TrustBaseEntry tip = this.entries.get(this.entries.size() - 1);
      if (UnsignedLongs.compare(entry.getEpoch(), tip.getEpoch()) <= 0) {
        if (UnsignedLongs.compare(entry.getEpoch(), first.getEpoch()) < 0) {
          throw new TrustBaseBeforeGenesisException("Epoch "
              + UnsignedLongs.toDecimal(entry.getEpoch()) + " is before the genesis epoch "
              + UnsignedLongs.toDecimal(first.getEpoch()) + ".");
        }
        int index = (int) (entry.getEpoch() - first.getEpoch());
        byte[] heldId = this.entries.get(index).entryId();
        byte[] offeredId = entry.entryId();
        if (!Arrays.equals(heldId, offeredId)) {
          // Different, but not authenticated: the caller that wants to know whether it is a fork
          // verifies the entry against the predecessor.
          throw new TrustBaseConflictException(entry.getEpoch(), new TrustBaseConflictDetails(
              false, entry.getEpoch(), null, heldId, entry.getNetworkId().getId(), null,
              offeredId));
        }
        if (index > 0) {
          entry.verifySuccessor(this.entries.get(index - 1));
        }
        return TrustBaseAppendOutcome.ALREADY_HELD;
      }
      if (entry.getEpoch() != tip.getEpoch() + 1) {
        throw new TrustBaseGapException(entry.getEpoch(), tip.getEpoch());
      }
      entry.verifySuccessor(tip);
      this.entries.add(entry);
      return TrustBaseAppendOutcome.APPENDED;
    }
  }

  /**
   * Get the held entry of an epoch.
   *
   * @param epoch epoch, unsigned bits
   * @return the entry, empty when the epoch is before the genesis or after the tip (the caller may
   *     fetch and append)
   */
  @Override
  public Optional<TrustBaseEntry> entryForEpoch(long epoch) {
    synchronized (this.lock) {
      if (this.entries.isEmpty()) {
        return Optional.empty();
      }
      TrustBaseEntry first = this.entries.get(0);
      if (UnsignedLongs.compare(epoch, first.getEpoch()) < 0) {
        return Optional.empty();
      }
      long offset = epoch - first.getEpoch();
      if (UnsignedLongs.compare(offset, this.entries.size()) >= 0) {
        return Optional.empty();
      }
      return Optional.of(this.entries.get((int) offset));
    }
  }

  /**
   * Get the round at which the held successor of an epoch starts, which ends the epoch's interval.
   *
   * @param epoch epoch, unsigned bits
   * @return start round of the entry for {@code epoch + 1}, empty when none is held (the epoch is
   *     open-ended as far as this history knows)
   */
  @Override
  public OptionalLong epochEndRound(long epoch) {
    if (epoch == -1L) {
      return OptionalLong.empty();
    }
    return entryForEpoch(epoch + 1)
        .map(entry -> OptionalLong.of(entry.getEpochStartRound()))
        .orElse(OptionalLong.empty());
  }

  /**
   * Get the held entries as one complete bundle (tag 39015), the form a cache stores. Round-trips
   * through {@link TrustBaseBundle#decode}.
   *
   * @return tagged bundle
   * @throws TrustBaseBundleException if the history is empty
   */
  @Override
  public byte[] toBundle() {
    return TrustBaseBundle.encode(getEntries());
  }
}
