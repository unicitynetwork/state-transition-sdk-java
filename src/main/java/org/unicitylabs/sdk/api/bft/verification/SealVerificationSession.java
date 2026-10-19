package org.unicitylabs.sdk.api.bft.verification;

import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.UnicitySeal;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealQuorumSignaturesVerificationRule;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.internal.SingleFlightMemo;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.UnsignedLongs;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Seal authentication under a snapshot of the caller's trust base, with a bounded memo of
 * successful checks and sharing of checks in flight.
 *
 * <p>The memo key binds the complete seal bytes including the signature witnesses, the authority
 * content and the kind of check (strict or legacy). Only successes are retained; failures and
 * failed in-flight checks are removed, the memo is bounded and evicts the oldest entry. Structure,
 * network, epoch and round checks of the strict path run on every call, hits included. The session
 * owns exactly one quorum rule and no verified flag can be supplied from outside.
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

  private static final String STRICT_KIND = "strict";
  private static final String LEGACY_KIND = "legacy";
  private static final String QUORUM_RULE = "UnicitySealQuorumSignaturesVerificationRule";

  private final RootTrustBase trustBase;
  private final String identity;
  private final boolean unitStake;
  private final int maxInFlight;
  private final SingleFlightMemo<SealVerificationResult> memo;
  private final AtomicLong inFlightJoins = new AtomicLong();
  private final AtomicLong memoHits = new AtomicLong();
  private final AtomicLong quorumChecks = new AtomicLong();

  private SealVerificationSession(RootTrustBase trustBase, int maxEntries, int maxInFlight) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be positive.");
    }
    if (maxInFlight <= 0) {
      throw new IllegalArgumentException("maxInFlight must be positive.");
    }
    this.trustBase = Objects.requireNonNull(trustBase, "trustBase cannot be null");
    this.maxInFlight = maxInFlight;
    // the trust base is immutable, so the authority identity is built once and every verdict and
    // every memo key refers to it
    this.identity = AuthorityIdentity.of(trustBase);
    boolean unit = true;
    for (RootTrustBase.NodeInfo node : trustBase.getRootNodes()) {
      unit &= node.getStakedAmount() == 1;
    }
    this.unitStake = unit;
    this.memo = new SingleFlightMemo<>(maxEntries, maxInFlight,
        result -> result.getStatus() == VerificationStatus.OK);
  }

  /**
   * Creates a session over the trust base. A trust base is immutable, so later changes of the
   * caller's configuration cannot affect the session.
   *
   * @param trustBase the authority
   * @return new session with the default limits
   */
  public static SealVerificationSession create(RootTrustBase trustBase) {
    return new SealVerificationSession(trustBase, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_IN_FLIGHT);
  }

  /**
   * Creates a session with explicit limits.
   *
   * @param trustBase the authority
   * @param maxEntries memo size, positive
   * @param maxInFlight most verifications in flight, positive
   * @return new session
   * @throws IllegalArgumentException if a limit is not positive
   */
  public static SealVerificationSession create(RootTrustBase trustBase, int maxEntries,
      int maxInFlight) {
    return new SealVerificationSession(trustBase, maxEntries, maxInFlight);
  }

  /**
   * The authority this session authenticates against.
   *
   * @return the trust base
   */
  public RootTrustBase getTrustBase() {
    return trustBase;
  }

  @Override
  public String getIdentity() {
    return identity;
  }

  /**
   * Native seal structural validation (bft-go-base {@code UnicitySeal.IsValid}), independent of any
   * trust base: hash present, root round at least 1, timestamp not before the genesis time,
   * signatures present with a signer id and bytes each. Signer map entries are outside the signed
   * payload, so this must hold before a success is returned or remembered.
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
   * Strict authentication: structure, network, epoch, epoch start round and quorum. The only
   * authentication this session offers, whatever policy an operation applies to token proofs; its
   * memo key is distinct from the legacy one, so a legacy success never admits a strict request.
   * The authority must weigh every node equally (stake 1), as the quorum rule counts signers;
   * another authority is refused rather than judged by the wrong rule.
   *
   * @param seal the seal
   * @return OK, or FAIL with the failing rule and its typed reason
   */
  @Override
  public SealVerificationResult authenticate(UnicitySeal seal) {
    Objects.requireNonNull(seal, "seal cannot be null");
    SealVerificationResult failure = checkAuthority(seal);
    if (failure != null) {
      return failure;
    }
    return quorum(STRICT_KIND, seal);
  }

  /**
   * Legacy check for historical token proofs: quorum signatures only, no structure, epoch or round
   * constraint (the network is checked with the certificate). Kept apart from
   * {@link #authenticate}: its results are memoized under a separate key, never satisfy a strict
   * request, and must not be reported as authentication.
   *
   * @param seal the seal
   * @return OK, or FAIL with the failing rule
   */
  public SealVerificationResult verifyLegacyQuorum(UnicitySeal seal) {
    Objects.requireNonNull(seal, "seal cannot be null");
    return quorum(LEGACY_KIND, seal);
  }

  /**
   * Work counters of the session.
   *
   * @return snapshot of the counters
   */
  public SealVerificationStats getStats() {
    return new SealVerificationStats(inFlightJoins.get(), memoHits.get(), quorumChecks.get());
  }

  private SealVerificationResult checkAuthority(UnicitySeal seal) {
    String structure = checkSealStructure(seal);
    if (structure != null) {
      return SealVerificationResult.fail("SealStructure", UnicityCertificateFailure.MALFORMED_SEAL,
          structure, List.of());
    }
    if (!seal.getNetworkId().equals(trustBase.getNetworkId())) {
      return SealVerificationResult.fail("SealAuthority", UnicityCertificateFailure.NETWORK_MISMATCH,
          String.format("Seal is of network %d, trust base is of network %d.",
              seal.getNetworkId().getId(), trustBase.getNetworkId().getId()), List.of());
    }
    if (seal.getEpoch() != trustBase.getEpoch()) {
      return SealVerificationResult.fail("SealAuthority", UnicityCertificateFailure.EPOCH_MISMATCH,
          String.format("Seal is of epoch %s, trust base is of epoch %s.",
              seal.getEpochDecimal(), UnsignedLongs.toDecimal(trustBase.getEpoch())), List.of());
    }
    if (UnsignedLongs.lessThan(seal.getRootChainRoundNumber(), trustBase.getEpochStartRound())) {
      return SealVerificationResult.fail("SealAuthority",
          UnicityCertificateFailure.BEFORE_EPOCH_START,
          String.format("Seal round %s is before the start of epoch %s (round %s).",
              seal.getRootChainRoundNumberDecimal(), UnsignedLongs.toDecimal(trustBase.getEpoch()),
              UnsignedLongs.toDecimal(trustBase.getEpochStartRound())), List.of());
    }
    if (!unitStake) {
      return SealVerificationResult.fail("SealAuthority",
          UnicityCertificateFailure.UNSUPPORTED_AUTHORITY,
          "The quorum rule counts signers; a stake-weighted authority is not supported.",
          List.of());
    }
    return null;
  }

  private SealVerificationResult quorum(String kind, UnicitySeal seal) {
    String key = HexConverter.encode(new DataHasher(HashAlgorithm.SHA256)
        .update(CborSerializer.encodeArray(
            CborSerializer.encodeTextString(kind),
            CborSerializer.encodeTextString(identity),
            CborSerializer.encodeByteString(seal.toCbor())))
        .digest().getData());
    SingleFlightMemo.Outcome<SealVerificationResult> outcome;
    try {
      outcome = memo.get(key, () -> {
        quorumChecks.incrementAndGet();
        VerificationResult<VerificationStatus> result =
            UnicitySealQuorumSignaturesVerificationRule.verify(trustBase, seal);
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
