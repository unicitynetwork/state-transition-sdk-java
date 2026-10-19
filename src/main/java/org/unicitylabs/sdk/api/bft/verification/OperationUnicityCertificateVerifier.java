package org.unicitylabs.sdk.api.bft.verification;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.unicitylabs.sdk.api.InclusionProof;
import org.unicitylabs.sdk.api.bft.PartitionViewPolicy;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.api.bft.UnicityCertificate;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.internal.SingleFlightMemo;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * Certificate verifier of one operation. Identical certificate bodies and seals of the token and of
 * every nested token are checked once while they are resident in the bounded memos; one body check
 * per distinct certificate, one quorum check per distinct seal.
 *
 * <p>Obtained from {@link UnicityCertificateVerifier#beginOperation}; the constructor is not public,
 * so a verifier of an operation cannot be forged or shared across operations by mistake. Thread
 * safe.
 *
 * <p>Per call, in this order: the network of the seal, the fixed partition (before any memo, so a
 * hit can never carry another partition), the certificate body, seal structure and paths to the
 * seal hash (memoized under the complete certificate bytes including the signature witnesses and
 * the policy), the caller's pins, and finally the seal under the policy of the operation. Under
 * {@link VerificationPolicy#STRICT} the seal is authenticated by the operation's
 * {@link SealVerificationSession}; under {@link VerificationPolicy#LEGACY_QUORUM} it goes through
 * the separate legacy quorum check. Only successes are retained.
 */
public final class OperationUnicityCertificateVerifier extends UnicityCertificateVerifier {

  private static final int MAX_BODIES = 256;
  private static final int MAX_BODIES_IN_FLIGHT = 64;

  private final VerificationPolicy policy;
  private final PartitionViewPolicy pins;
  private final SingleFlightMemo<CertificateChecks.Body> bodies = new SingleFlightMemo<>(
      MAX_BODIES, MAX_BODIES_IN_FLIGHT, CertificateChecks.Body::isOk);
  private final Map<RootTrustBase, SealVerificationSession> sessions = new IdentityHashMap<>();
  private final AtomicLong bodyChecks = new AtomicLong();
  private final AtomicLong bodyHits = new AtomicLong();
  private final AtomicLong encodes = new AtomicLong();

  private OperationUnicityCertificateVerifier(VerificationPolicy policy, PartitionViewPolicy pins) {
    this.policy = policy;
    this.pins = pins;
    // a pin set that is not consistent is an error of the configuration, found here
    pins.getIdentity();
  }

  static OperationUnicityCertificateVerifier start(VerificationPolicy policy,
      PartitionViewPolicy pins) {
    return new OperationUnicityCertificateVerifier(policy, pins);
  }

  /**
   * An operation verifier is already scoped to its operation: asking for the same policy and pins
   * returns it, another policy or other pins begin an independent operation that shares nothing,
   * so a different policy never inherits memoized work.
   */
  @Override
  public UnicityCertificateVerifier beginOperation(VerificationPolicy policy,
      PartitionViewPolicy pins) {
    Objects.requireNonNull(policy, "policy cannot be null");
    PartitionViewPolicy requested = pins == null ? PartitionViewPolicy.none() : pins;
    if (policy == this.policy && requested.getIdentity().equals(this.pins.getIdentity())) {
      return this;
    }
    return new OperationUnicityCertificateVerifier(policy, requested);
  }

  /**
   * Verifies the certificate of an inclusion proof under the policy of the operation. Network and
   * epoch/policy checks run on every call, memo hits included.
   *
   * @param trustBase the authority
   * @param inclusionProof the proof
   * @return verification result
   */
  @Override
  public UnicityCertificateVerificationResult verify(RootTrustBase trustBase,
      InclusionProof inclusionProof) {
    return verifyCertificate(trustBase, inclusionProof.getUnicityCertificate());
  }

  /**
   * Verifies a raw certificate under the policy of the operation, with the operation's seal
   * session, body memo and pins. Used for strict evidence such as non-inclusion.
   *
   * @param trustBase the authority
   * @param certificate the certificate
   * @return verification result
   */
  @Override
  public UnicityCertificateVerificationResult verifyCertificate(RootTrustBase trustBase,
      UnicityCertificate certificate) {
    List<VerificationResult<?>> results = new ArrayList<>();

    VerificationResult<VerificationStatus> network = CertificateChecks.network(trustBase,
        certificate);
    results.add(network);
    if (network.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(
          UnicityCertificateFailure.NETWORK_MISMATCH, results);
    }

    // before the memo: a certificate of another partition is never served from it
    VerificationResult<VerificationStatus> partition = CertificateChecks.partition(certificate);
    results.add(partition);
    if (partition.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(
          UnicityCertificateFailure.PARTITION_MISMATCH, results);
    }

    CertificateChecks.Body body = body(certificate);
    results.addAll(body.getRules());
    if (!body.isOk()) {
      return UnicityCertificateVerificationResult.fail(body.getFailure(), results);
    }

    VerificationResult<VerificationStatus> pinned = CertificateChecks.pins(pins, certificate);
    results.add(pinned);
    if (pinned.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(
          UnicityCertificateFailure.PIN_MISMATCH, results);
    }

    SealVerificationSession session = sessionFor(trustBase);
    SealVerificationResult seal = policy == VerificationPolicy.STRICT
        ? session.authenticate(certificate.getUnicitySeal())
        : session.verifyLegacyQuorum(certificate.getUnicitySeal());
    results.add(seal);
    if (seal.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(seal.getFailure(), results);
    }

    return UnicityCertificateVerificationResult.ok(results);
  }

  /**
   * Verifies the body of a certificate that is not itself the subject of an authentication: its
   * partition, body and seal structure, the shard and partition paths to the seal hash, and the
   * pins of the operation, with the operation's body memo. The seal is <em>not</em> authenticated
   * and the network is not compared with an authority. Meant for certificates derived from an
   * authenticated partition view, whose seal the operation's session authenticated strictly; a
   * result of this method is never evidence that a seal is trusted.
   *
   * @param certificate the certificate
   * @return verification result; its failure is one of the certificate faults, never an authority
   *     rejection
   */
  public UnicityCertificateVerificationResult verifyBody(UnicityCertificate certificate) {
    List<VerificationResult<?>> results = new ArrayList<>();
    VerificationResult<VerificationStatus> partition = CertificateChecks.partition(certificate);
    results.add(partition);
    if (partition.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(
          UnicityCertificateFailure.PARTITION_MISMATCH, results);
    }
    CertificateChecks.Body body = body(certificate);
    results.addAll(body.getRules());
    if (!body.isOk()) {
      return UnicityCertificateVerificationResult.fail(body.getFailure(), results);
    }
    VerificationResult<VerificationStatus> pinned = CertificateChecks.pins(pins, certificate);
    results.add(pinned);
    if (pinned.getStatus() != VerificationStatus.OK) {
      return UnicityCertificateVerificationResult.fail(
          UnicityCertificateFailure.PIN_MISMATCH, results);
    }
    return UnicityCertificateVerificationResult.ok(results);
  }

  /**
   * Seal authenticator of an authority within this operation. Whoever authenticates seals strictly
   * in this operation (views, non-inclusion) shares the quorum checks of the proofs verified
   * through this verifier.
   *
   * @param trustBase the authority; one session is built per distinct trust base object
   * @return seal session of the operation
   */
  public SealVerificationSession sessionFor(RootTrustBase trustBase) {
    Objects.requireNonNull(trustBase, "trustBase cannot be null");
    synchronized (sessions) {
      SealVerificationSession session = sessions.get(trustBase);
      if (session == null) {
        session = SealVerificationSession.create(trustBase);
        sessions.put(trustBase, session);
      }
      return session;
    }
  }

  /**
   * Policy of the operation.
   *
   * @return policy
   */
  public VerificationPolicy getPolicy() {
    return policy;
  }

  /**
   * Pins of the operation.
   *
   * @return pins, {@link PartitionViewPolicy#none()} if there are none
   */
  public PartitionViewPolicy getPins() {
    return pins;
  }

  /**
   * Work counters of the operation.
   *
   * @return snapshot of the counters
   */
  public OperationVerificationStats getStats() {
    long builds;
    long quorum = 0;
    synchronized (sessions) {
      builds = sessions.size();
      for (SealVerificationSession session : sessions.values()) {
        quorum += session.getStats().getQuorumChecks();
      }
    }
    return new OperationVerificationStats(builds, bodyChecks.get(), bodyHits.get(), encodes.get(),
        quorum);
  }

  private CertificateChecks.Body body(UnicityCertificate certificate) {
    encodes.incrementAndGet();
    String key = HexConverter.encode(new DataHasher(HashAlgorithm.SHA256)
        .update(CborSerializer.encodeArray(
            CborSerializer.encodeTextString(policy.name()),
            CborSerializer.encodeByteString(certificate.toCbor())))
        .digest().getData());
    SingleFlightMemo.Outcome<CertificateChecks.Body> outcome = bodies.get(key, () -> {
      bodyChecks.incrementAndGet();
      return CertificateChecks.body(certificate);
    });
    if (outcome.getSource() != SingleFlightMemo.Source.COMPUTED) {
      bodyHits.incrementAndGet();
    }
    return outcome.getValue();
  }
}
