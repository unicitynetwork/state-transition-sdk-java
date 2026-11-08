package org.unicitylabs.sdk.api.bft.verification;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealQuorumSignaturesVerificationRule;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealTrustBaseEntryRule;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.internal.SingleFlightMemo;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.UnsignedLongs;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Seal authentication under a trust-base history, with a bounded memo of successful quorum checks
 * and sharing of checks in flight.
 *
 * <p>Each seal is checked against the entry of its own epoch: structure, then its place in the
 * history (network, the entry of its epoch, the round inside that epoch's interval), then the
 * weighted quorum of that entry. Placement runs on every call, memo hits included, so a growing
 * history or a conflicting session is seen by every verdict. What is memoized is the signature
 * verdict "this exact seal carries a quorum of valid signatures of this entry", a pure function of
 * the complete seal bytes (signature witnesses included) and the entry id: the key binds exactly
 * those, so altered witnesses or another entry cannot hit an entry. Only successes are retained;
 * failures and failed in-flight checks are removed, the memo is bounded and evicts the oldest
 * entry. A seal of an epoch the history does not hold fails with the typed
 * {@link NoTrustBaseEntryResult}. The session owns exactly one quorum rule and no verified flag
 * can be supplied from outside.
 *
 * <p>Thread safe. Instances are scoped to one operation; they are not a process-wide cache.
 */
public final class SealVerificationSession implements SealAuthenticator {

  /** Default size of the memo of successful checks. */
  public static final int DEFAULT_MAX_ENTRIES = 64;
  /** Default most concurrent verifications in flight. */
  public static final int DEFAULT_MAX_IN_FLIGHT = 64;
  /** Earliest plausible seal timestamp: Thursday, April 20, 2023 6:11:24 AM GMT+00:00. */
  public static final long GENESIS_TIME = 1681971084L;

  private static final String QUORUM_RULE = "UnicitySealQuorumSignaturesVerificationRule";

  private final TrustBaseHistoryView trustBase;
  private final SealQuorumRule quorumRule;
  private final int maxInFlight;
  private final SingleFlightMemo<SealVerificationResult> memo;
  private final AtomicLong inFlightJoins = new AtomicLong();
  private final AtomicLong memoHits = new AtomicLong();
  private final AtomicLong quorumChecks = new AtomicLong();

  private SealVerificationSession(TrustBaseHistoryView trustBase, int maxEntries, int maxInFlight,
      SealQuorumRule quorumRule) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be positive.");
    }
    if (maxInFlight <= 0) {
      throw new IllegalArgumentException("maxInFlight must be positive.");
    }
    this.trustBase = Objects.requireNonNull(trustBase, "trustBase cannot be null");
    this.quorumRule = Objects.requireNonNull(quorumRule, "quorumRule cannot be null");
    this.maxInFlight = maxInFlight;
    this.memo = new SingleFlightMemo<>(maxEntries, maxInFlight,
        result -> result.getStatus() == VerificationStatus.OK);
  }

  /**
   * Creates a session over the trust-base history.
   *
   * @param trustBase the authority: a view of the held history
   * @return new session with the default limits
   */
  public static SealVerificationSession create(TrustBaseHistoryView trustBase) {
    return new SealVerificationSession(trustBase, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_IN_FLIGHT,
        UnicitySealQuorumSignaturesVerificationRule::verify);
  }

  /**
   * Creates a session with explicit limits.
   *
   * @param trustBase the authority: a view of the held history
   * @param maxEntries memo size, positive
   * @param maxInFlight most verifications in flight, positive
   * @return new session
   * @throws IllegalArgumentException if a limit is not positive
   */
  public static SealVerificationSession create(TrustBaseHistoryView trustBase, int maxEntries,
      int maxInFlight) {
    return new SealVerificationSession(trustBase, maxEntries, maxInFlight,
        UnicitySealQuorumSignaturesVerificationRule::verify);
  }

  /**
   * Creates a session with explicit limits and its own quorum check.
   *
   * @param trustBase the authority: a view of the held history
   * @param maxEntries memo size, positive
   * @param maxInFlight most verifications in flight, positive
   * @param quorumRule the check that runs on every distinct miss of the memo
   * @return new session
   * @throws IllegalArgumentException if a limit is not positive
   */
  public static SealVerificationSession create(TrustBaseHistoryView trustBase, int maxEntries,
      int maxInFlight, SealQuorumRule quorumRule) {
    return new SealVerificationSession(trustBase, maxEntries, maxInFlight, quorumRule);
  }

  /**
   * The authority this session authenticates against.
   *
   * @return the trust-base view
   */
  public TrustBaseHistoryView getTrustBase() {
    return trustBase;
  }

  /**
   * Identity of the anchored authority: the pinned genesis and the held tip. It changes when the
   * history grows, which only costs a cache miss.
   */
  @Override
  public String getIdentity() {
    return AuthorityIdentity.ofView(trustBase);
  }

  /**
   * Native seal structural validation (bft-go-base {@code UnicitySeal.IsValid}), independent of any
   * trust base: hash present, root round at least 1, timestamp not before the genesis time,
   * signatures present (at most {@link UnicitySeal#MAX_WITNESSES}) with a signer id and bytes each.
   * Signer map entries are outside the signed payload, so this must hold before a success is
   * returned or remembered.
   *
   * @param seal the seal
   * @return description of the violation, or null if the seal is structurally valid
   */
  public static String checkSealStructure(UnicitySeal seal) {
    if (seal.getHash().length == 0) {
      return "Seal hash is missing.";
    }
    if (seal.getRootChainRoundNumber() == 0) {
      return "Seal root chain round is invalid.";
    }
    if (UnsignedLongs.lessThan(seal.getTimestamp(), GENESIS_TIME)) {
      return "Seal timestamp is invalid.";
    }
    Map<String, byte[]> witnesses = seal.getWitnesses();
    if (witnesses == null || witnesses.isEmpty()) {
      return "Seal has no signatures.";
    }
    if (witnesses.size() > UnicitySeal.MAX_WITNESSES) {
      return String.format("Seal has %d signatures, more than %d.", witnesses.size(),
          UnicitySeal.MAX_WITNESSES);
    }
    for (Map.Entry<String, byte[]> entry : witnesses.entrySet()) {
      if (entry.getKey().isEmpty()) {
        return "Seal has a signature without signer ID.";
      }
      if (entry.getValue().length == 0) {
        return String.format("Seal has an empty signature for \"%s\".", entry.getKey());
      }
    }
    return null;
  }

  /**
   * Authentication: structure, the seal's place in the history (network, the entry of its epoch,
   * the round inside that epoch) and the weighted quorum of that entry. A seal of an epoch the
   * history does not hold fails with the typed {@link NoTrustBaseEntryResult} as a child of the
   * result, and a wrong network, an epoch before the genesis, a round outside the interval or a
   * bad quorum is final.
   *
   * @param seal the seal
   * @return OK, or FAIL with the failing rule and its typed reason
   */
  @Override
  public SealVerificationResult authenticate(UnicitySeal seal) {
    Objects.requireNonNull(seal, "seal cannot be null");
    String structure = checkSealStructure(seal);
    if (structure != null) {
      return SealVerificationResult.fail("SealStructure", UnicityCertificateFailure.MALFORMED_SEAL,
          structure, List.of());
    }

    TrustBaseEntryResult placed = UnicitySealTrustBaseEntryRule.verify(trustBase, seal);
    if (placed.getEntry() == null) {
      return SealVerificationResult.fail("SealAuthority", placed.getFailure(),
          placed.getMessage(), List.of(placed));
    }
    return quorum(placed.getEntry(), seal);
  }

  /**
   * Work counters of the session.
   *
   * @return snapshot of the counters
   */
  public SealVerificationStats getStats() {
    return new SealVerificationStats(inFlightJoins.get(), memoHits.get(), quorumChecks.get());
  }

  private SealVerificationResult quorum(TrustBaseEntry entry, UnicitySeal seal) {
    // Before the memo key is built (which encodes and hashes the whole seal) and before any
    // signature work.
    Map<String, byte[]> witnesses = seal.getWitnesses();
    if (witnesses != null && witnesses.size() > UnicitySeal.MAX_WITNESSES) {
      return SealVerificationResult.fail("SealAuthority", UnicityCertificateFailure.MALFORMED_SEAL,
          String.format("Seal has %d signatures, more than %d.", witnesses.size(),
              UnicitySeal.MAX_WITNESSES), List.of());
    }

    String key = HexConverter.encode(new DataHasher(HashAlgorithm.SHA256)
        .update(CborSerializer.encodeArray(
            CborSerializer.encodeTextString(AuthorityIdentity.ofEntry(entry)),
            CborSerializer.encodeByteString(seal.toCbor())))
        .digest().getData());
    SingleFlightMemo.Outcome<SealVerificationResult> outcome;
    try {
      outcome = memo.get(key, () -> {
        quorumChecks.incrementAndGet();
        VerificationResult<VerificationStatus> result =
            quorumRule.verify(entry, seal);
        if (result.getStatus() == VerificationStatus.OK) {
          return SealVerificationResult.ok(QUORUM_RULE, result.getResults());
        }
        return SealVerificationResult.fail(QUORUM_RULE, UnicityCertificateFailure.QUORUM_NOT_REACHED,
            result.getMessage(), result.getResults());
      });
    } catch (SingleFlightMemo.BusyException e) {
      throw new SealVerificationBusyException(maxInFlight);
    }

    switch (outcome.getSource()) {
      case HIT:
        memoHits.incrementAndGet();
        break;
      case JOINED:
        inFlightJoins.incrementAndGet();
        break;
      default:
        break;
    }
    return outcome.getValue();
  }
}
