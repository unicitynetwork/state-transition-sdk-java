package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.PartitionViewException.Reason;
import org.unicitylabs.sdk.api.bft.verification.SealVerificationSession;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

class PartitionViewAuthenticatorTest {

  private static final TestChain.Authority AUTHORITY = new TestChain.Authority();
  private static final long NOW = TestChain.TIMESTAMP;

  private static PartitionViewAuthenticator authenticator(PartitionViewPolicy policy) {
    return new PartitionViewAuthenticator(SealVerificationSession.create(AUTHORITY.trustBase()),
        policy);
  }

  private static Reason failure(PartitionViewAuthenticator authenticator, PartitionView view,
      UnicityCertificate enclosing) {
    return assertThrows(PartitionViewException.class,
        () -> authenticator.authenticate(view, enclosing)).getReason();
  }

  private static TestChain.Sealed four() {
    return TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(3, NOW));
  }

  @Test
  void aCommittedViewIsAuthenticatedAndDerivesVerifiableCertificatesLazily() {
    TestChain.Sealed sealed = four();
    PartitionViewAuthenticator authenticator = authenticator(PartitionViewPolicy.none());
    AuthenticatedPartitionView view = authenticator.authenticate(sealed.view,
        sealed.certificate("01"));

    assertEquals(7, view.getPartitionId());
    assertEquals(4, view.getLeaves().size());
    assertEquals(4, view.getScheme().size());
    assertEquals(sealed.seal, view.getSeal());
    assertEquals(NetworkId.LOCAL, view.getNetworkId());
    assertEquals(0, view.getCertificateDerivations());

    UnicityCertificate derived = view.certificateOf(ShardId.fromBitString("10"));
    assertEquals(1, view.getCertificateDerivations(), "only the asked shard is derived");
    assertEquals(sealed.certificate("10"), derived);
    assertEquals(VerificationStatus.OK, new org.unicitylabs.sdk.api.bft.verification
        .UnicityCertificateVerifier().verifyCertificate(AUTHORITY.trustBase(), derived).getStatus());
    assertEquals(sealed.view.getLeaves().get(2), view.leafOf(ShardId.fromBitString("10")));
    assertEquals(1, view.getCertificateDerivations());
    assertEquals(4, view.assembleCertificates().size());

    PartitionViewException missing = assertThrows(PartitionViewException.class,
        () -> view.certificateOf(ShardId.fromBitString("111")));
    assertEquals(Reason.TOPOLOGY, missing.getReason());
    assertThrows(UnsupportedOperationException.class, () -> view.getLeaves().clear());
  }

  @Test
  void aSingleShardPartitionIsAuthenticatedFromItsOwnCertificate() {
    TestChain.Sealed sealed = TestChain.seal(AUTHORITY, 7, 9,
        List.of(TestChain.leaf("", 3, NOW, 1)));
    AuthenticatedPartitionView view = authenticator(PartitionViewPolicy.none())
        .authenticate(sealed.view, sealed.certificate(""));
    assertEquals(0, view.getScheme().size());
    assertEquals(sealed.certificate(""), view.certificateOf(ShardId.empty()));
  }

  @Test
  void aViewWithAnAlteredLeafDoesNotHashIntoTheSeal() {
    TestChain.Sealed sealed = four();
    List<PartitionViewLeaf> leaves = new ArrayList<>(sealed.view.getLeaves());
    // a peer shard's leaf forged
    leaves.set(3, TestChain.leaf("11", 3, NOW, 99));
    PartitionViewAuthenticator authenticator = authenticator(PartitionViewPolicy.none());
    assertEquals(Reason.COMMITMENT,
        failure(authenticator, PartitionView.of(7, leaves), sealed.certificate("00")));
    // the enclosing certificate's own leaf forged
    leaves = new ArrayList<>(sealed.view.getLeaves());
    leaves.set(0, TestChain.leaf("00", 3, NOW, 98));
    assertEquals(Reason.COMMITMENT,
        failure(authenticator, PartitionView.of(7, leaves), sealed.certificate("00")));
  }

  @Test
  void missingExtraAndReorderedLeavesAreTopologyFaults() {
    TestChain.Sealed sealed = four();
    PartitionViewAuthenticator authenticator = authenticator(PartitionViewPolicy.none());
    List<PartitionViewLeaf> leaves = sealed.view.getLeaves();

    assertEquals(Reason.TOPOLOGY, failure(authenticator,
        PartitionView.of(7, leaves.subList(0, 3)), sealed.certificate("00")));
    List<PartitionViewLeaf> extra = new ArrayList<>(leaves);
    extra.add(TestChain.leaf("110", 3, NOW, 7));
    assertEquals(Reason.TOPOLOGY,
        failure(authenticator, PartitionView.of(7, extra), sealed.certificate("00")));
    List<PartitionViewLeaf> reordered = new ArrayList<>(leaves);
    PartitionViewLeaf first = reordered.remove(0);
    reordered.add(first);
    assertEquals(Reason.TOPOLOGY,
        failure(authenticator, PartitionView.of(7, reordered), sealed.certificate("00")));
    List<PartitionViewLeaf> duplicate = new ArrayList<>(leaves);
    duplicate.set(1, leaves.get(0));
    assertEquals(Reason.TOPOLOGY,
        failure(authenticator, PartitionView.of(7, duplicate), sealed.certificate("00")));
  }

  @Test
  void theEnclosingCertificateMustBeALeafOfTheViewAndMatchItCompletely() {
    TestChain.Sealed sealed = four();
    PartitionViewAuthenticator authenticator = authenticator(PartitionViewPolicy.none());
    UnicityCertificate own = sealed.certificate("01");

    // another shard's certificate path
    UnicityCertificate wrongPath = new UnicityCertificate(own.getInputRecord(),
        own.getTechnicalRecordHash(), own.getShardConfigurationHash(),
        sealed.certificate("10").getShardTreeCertificate(), own.getUnicityTreeCertificate(),
        own.getUnicitySeal());
    assertEquals(Reason.COMMITMENT, failure(authenticator, sealed.view, wrongPath));

    // a shard that is not in the view
    TestChain.Sealed single = TestChain.seal(AUTHORITY, 7, 9, List.of(TestChain.leaf("", 3, NOW, 1)));
    assertEquals(Reason.COMMITMENT, failure(authenticator, sealed.view, single.certificate("")));

    // a path with the right shard but too long
    List<byte[]> longer = new ArrayList<>(own.getShardTreeCertificate().getSiblingHashList());
    longer.add(new byte[32]);
    UnicityCertificate tooLong = new UnicityCertificate(own.getInputRecord(),
        own.getTechnicalRecordHash(), own.getShardConfigurationHash(),
        new ShardTreeCertificate(own.getShardTreeCertificate().getShard(), longer),
        own.getUnicityTreeCertificate(), own.getUnicitySeal());
    assertEquals(Reason.COMMITMENT, failure(authenticator, sealed.view, tooLong));

    // a different technical record hash / configuration hash
    UnicityCertificate otherTechnical = new UnicityCertificate(own.getInputRecord(), new byte[32],
        own.getShardConfigurationHash(), own.getShardTreeCertificate(),
        own.getUnicityTreeCertificate(), own.getUnicitySeal());
    assertEquals(Reason.COMMITMENT, failure(authenticator, sealed.view, otherTechnical));

    // the partition certificate of another partition
    UnicityCertificate otherPartition = new UnicityCertificate(own.getInputRecord(),
        own.getTechnicalRecordHash(), own.getShardConfigurationHash(),
        own.getShardTreeCertificate(), new UnicityTreeCertificate(8, List.of()),
        own.getUnicitySeal());
    assertEquals(Reason.COMMITMENT, failure(authenticator, sealed.view, otherPartition));
  }

  @Test
  void aPartitionPathStepThatChangesTheRootIsACommitmentFault() {
    TestChain.Sealed sealed = four();
    UnicityCertificate own = sealed.certificate("00");
    UnicityCertificate withStep = new UnicityCertificate(own.getInputRecord(),
        own.getTechnicalRecordHash(), own.getShardConfigurationHash(),
        own.getShardTreeCertificate(), new UnicityTreeCertificate(7, List.of(TestChain.step(3, 1))),
        own.getUnicitySeal());
    assertEquals(Reason.COMMITMENT,
        failure(authenticator(PartitionViewPolicy.none()), sealed.view, withStep));
  }

  @Test
  void aViewOfAnotherPartitionIsRefusedWhateverTheSeal() {
    TestChain.Sealed eight = TestChain.seal(AUTHORITY, 8, 9, TestChain.fourShards(3, NOW));
    assertEquals(Reason.POLICY, failure(authenticator(PartitionViewPolicy.none()), eight.view,
        eight.certificate("00")));
  }

  @Test
  void theSealIsAuthenticatedLastAndStrictly() {
    PartitionViewAuthenticator authenticator = authenticator(PartitionViewPolicy.none());
    // below quorum
    TestChain.Sealed weak = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(3, NOW), 0, 1);
    assertEquals(Reason.AUTHENTICATION, failure(authenticator, weak.view, weak.certificate("00")));
    // an earlier epoch: the legacy quorum check would pass, the strict one must not
    TestChain.Authority epoch2 = new TestChain.Authority(NetworkId.LOCAL, 2, 5, 1);
    PartitionViewAuthenticator current = new PartitionViewAuthenticator(
        SealVerificationSession.create(epoch2.trustBase()), PartitionViewPolicy.none());
    TestChain.Sealed historical = four();
    assertEquals(Reason.AUTHENTICATION,
        failure(current, historical.view, historical.certificate("00")));
    // another network
    PartitionViewAuthenticator otherNetwork = new PartitionViewAuthenticator(
        SealVerificationSession.create(
            new TestChain.Authority(NetworkId.MAINNET, 1, 1, 1).trustBase()),
        PartitionViewPolicy.none());
    assertEquals(Reason.AUTHENTICATION,
        failure(otherNetwork, historical.view, historical.certificate("00")));
  }

  @Test
  void aTamperedCommitmentIsRefusedBeforeAnySignatureWork() {
    TestChain.Sealed sealed = four();
    SealVerificationSession session = SealVerificationSession.create(AUTHORITY.trustBase());
    PartitionViewAuthenticator authenticator =
        new PartitionViewAuthenticator(session, PartitionViewPolicy.none());
    List<PartitionViewLeaf> leaves = new ArrayList<>(sealed.view.getLeaves());
    leaves.set(3, TestChain.leaf("11", 3, NOW, 99));
    assertEquals(Reason.COMMITMENT,
        failure(authenticator, PartitionView.of(7, leaves), sealed.certificate("00")));
    assertEquals(0, session.getStats().getQuorumChecks());
  }

  @Test
  void successesAreRememberedAndFailuresAreNot() {
    TestChain.Sealed sealed = four();
    SealVerificationSession session = SealVerificationSession.create(AUTHORITY.trustBase());
    PartitionViewAuthenticator authenticator =
        new PartitionViewAuthenticator(session, PartitionViewPolicy.none());
    AuthenticatedPartitionView first = authenticator.authenticate(sealed.view,
        sealed.certificate("00"));
    assertSame(first, authenticator.authenticate(sealed.view, sealed.certificate("00")));
    assertEquals(1, authenticator.getAuthentications());
    assertEquals(1, authenticator.getMemoHits());
    assertEquals(1, session.getStats().getQuorumChecks());

    TestChain.Sealed weak = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(3, NOW), 0, 1);
    for (int i = 0; i < 2; i++) {
      failure(authenticator, weak.view, weak.certificate("00"));
    }
    assertEquals(3, authenticator.getAuthentications());
  }

  @Test
  void pinsNarrowWhatIsAccepted() {
    TestChain.Sealed sealed = four();
    byte[] right = sealed.view.getLeaves().get(2).getShardConfigurationHash();
    assertTrue(authenticator(PartitionViewPolicy.none().withPins(List.of(
        new ShardConfigurationPin(ShardId.fromBitString("10"), right))))
        .authenticate(sealed.view, sealed.certificate("00")).getLeaves().size() == 4);

    assertEquals(Reason.POLICY, failure(authenticator(PartitionViewPolicy.none().withPins(List.of(
        new ShardConfigurationPin(ShardId.fromBitString("10"), new byte[32])))), sealed.view,
        sealed.certificate("00")));

    PartitionViewPolicy otherScheme = PartitionViewPolicy.none().withScheme(List.of(
        ShardId.fromBitString("0"), ShardId.fromBitString("1")));
    assertEquals(Reason.POLICY,
        failure(authenticator(otherScheme), sealed.view, sealed.certificate("00")));

    // a pin on a shard that is not in the view is refused, not ignored
    assertEquals(Reason.POLICY, failure(authenticator(PartitionViewPolicy.none().withPins(List.of(
        new ShardConfigurationPin(ShardId.fromBitString("110"), new byte[32])))), sealed.view,
        sealed.certificate("00")));
  }

  @Test
  void anInconsistentPolicyIsRefusedWhenItIsBuilt() {
    assertEquals(Reason.TOPOLOGY, assertThrows(PartitionViewException.class,
        () -> PartitionViewPolicy.none().withScheme(List.of(ShardId.fromBitString("0")))).getReason());
    ShardConfigurationPin pin = new ShardConfigurationPin(ShardId.fromBitString("0"), new byte[32]);
    assertEquals(Reason.TOPOLOGY, assertThrows(PartitionViewException.class,
        () -> PartitionViewPolicy.none().withPins(List.of(pin, pin))).getReason());
    assertEquals(Reason.TOPOLOGY, assertThrows(PartitionViewException.class, () ->
        PartitionViewPolicy.none().withScheme(List.of(ShardId.fromBitString("0"),
            ShardId.fromBitString("1"))).withPins(List.of(new ShardConfigurationPin(
            ShardId.fromBitString("00"), new byte[32])))).getReason());
    assertThrows(IllegalArgumentException.class,
        () -> new ShardConfigurationPin(ShardId.empty(), new byte[31]));
  }

  @Test
  void policyAndAuthorityAreTheIdentityOfTheAuthenticator() {
    PartitionViewAuthenticator plain = authenticator(PartitionViewPolicy.none());
    PartitionViewAuthenticator pinned = authenticator(PartitionViewPolicy.none().withPins(List.of(
        new ShardConfigurationPin(ShardId.fromBitString("10"), new byte[32]))));
    PartitionViewAuthenticator otherAuthority = new PartitionViewAuthenticator(
        SealVerificationSession.create(new TestChain.Authority(NetworkId.LOCAL, 1, 1, 2)
            .trustBase()), PartitionViewPolicy.none());
    assertEquals(plain.getIdentity(), authenticator(PartitionViewPolicy.none()).getIdentity());
    assertTrue(!plain.getIdentity().equals(pinned.getIdentity()));
    assertTrue(!plain.getIdentity().equals(otherAuthority.getIdentity()));
  }

  @Test
  void roundsAboveTwoToThe63AreCommittedAndAuthenticated() {
    TestChain.Authority high = new TestChain.Authority(NetworkId.LOCAL, -1L, Long.MIN_VALUE, 1);
    TestChain.Sealed sealed = TestChain.seal(high, 7, -1L,
        TestChain.fourShards(Long.MIN_VALUE, NOW));
    AuthenticatedPartitionView view = new PartitionViewAuthenticator(
        SealVerificationSession.create(high.trustBase()), PartitionViewPolicy.none())
        .authenticate(sealed.view, sealed.certificate("11"));
    assertEquals("18446744073709551615", view.getSeal().getRootChainRoundNumberDecimal());
    assertEquals("9223372036854775808",
        view.leafOf(ShardId.fromBitString("00")).getInputRecord().getRoundNumberDecimal());
  }

  @Test
  void anEnclosingCertificateGivenAsBytesMustBeCanonical() {
    TestChain.Sealed sealed = four();
    PartitionViewAuthenticator authenticator = authenticator(PartitionViewPolicy.none());
    byte[] bytes = sealed.certificate("00").toCbor();
    authenticator.authenticate(sealed.view.toCbor(), bytes);
    byte[] trailing = java.util.Arrays.copyOf(bytes, bytes.length + 1);
    assertEquals(Reason.ENCODING, assertThrows(PartitionViewException.class,
        () -> PartitionViewAuthenticator.decodeEnclosing(trailing)).getReason());
  }

  @Test
  void aSaturatedMemoAuthenticatesForTheCallerInsteadOfThrowingInternals() throws Exception {
    TestChain.Sealed first = four();
    TestChain.Sealed second = TestChain.seal(AUTHORITY, 7, 10, TestChain.fourShards(4, NOW));
    SealVerificationSession session = SealVerificationSession.create(AUTHORITY.trustBase());
    java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean firstCall = new java.util.concurrent.atomic.AtomicBoolean(true);
    // blocks inside the first authentication, so that it stays in flight
    org.unicitylabs.sdk.api.bft.verification.SealAuthenticator blocking =
        new org.unicitylabs.sdk.api.bft.verification.SealAuthenticator() {
          @Override
          public String getIdentity() {
            return session.getIdentity();
          }

          @Override
          public org.unicitylabs.sdk.util.verification.VerificationResult<VerificationStatus>
              authenticate(UnicitySeal seal) {
            if (firstCall.getAndSet(false)) {
              inside.countDown();
              try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            return session.authenticate(seal);
          }
        };
    PartitionViewAuthenticator authenticator =
        new PartitionViewAuthenticator(blocking, PartitionViewPolicy.none(), 4, 1);
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      java.util.concurrent.Future<AuthenticatedPartitionView> running = pool.submit(
          () -> authenticator.authenticate(first.view, first.certificate("00")));
      assertTrue(inside.await(10, java.util.concurrent.TimeUnit.SECONDS));
      // the memo is full of work in flight: a different view is authenticated by this caller
      AuthenticatedPartitionView other = authenticator.authenticate(second.view,
          second.certificate("00"));
      assertEquals(second.seal, other.getSeal());
      release.countDown();
      assertEquals(first.seal, running.get(10, java.util.concurrent.TimeUnit.SECONDS).getSeal());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * Guard: the partition of the enclosing certificate is the partition of the view. A seal that
   * commits to partitions 7 and 8 must not authenticate the view of partition 8 as a view of
   * partition 7.
   */
  @Test
  void aViewOfAnotherPartitionUnderTheSameSealIsNotRelabelled() {
    List<PartitionViewLeaf> leaves7 = TestChain.fourShards(3, NOW);
    List<PartitionViewLeaf> leaves8 = List.of(TestChain.leaf("0", 4, NOW, 11),
        TestChain.leaf("1", 4, NOW, 12));
    ShardTree tree7 = ShardTree.create(leaves7);
    ShardTree tree8 = ShardTree.create(leaves8);
    byte[] leafHash7 = TestChain.unicityRoot(7, tree7.rootHash(), List.of());
    byte[] leafHash8 = TestChain.unicityRoot(8, tree8.rootHash(), List.of());
    // the one node that joins the two partitions has the key 7: smaller keys fold to its left
    List<UnicityTreeCertificate.HashStep> steps7 =
        List.of(new UnicityTreeCertificate.HashStep(7, leafHash8));
    List<UnicityTreeCertificate.HashStep> steps8 =
        List.of(new UnicityTreeCertificate.HashStep(7, leafHash7));
    byte[] root = TestChain.unicityRoot(7, tree7.rootHash(), steps7);
    org.junit.jupiter.api.Assertions.assertArrayEquals(root,
        TestChain.unicityRoot(8, tree8.rootHash(), steps8), "one seal commits to both partitions");
    UnicitySeal seal = AUTHORITY.seal(9, root);

    UnicityTreeCertificate utc7 = new UnicityTreeCertificate(7, steps7);
    UnicityTreeCertificate utc8 = new UnicityTreeCertificate(8, steps8);
    PartitionViewLeaf first7 = leaves7.get(0);
    PartitionViewLeaf first8 = leaves8.get(0);
    UnicityCertificate certificate7 = new UnicityCertificate(first7.getInputRecord(),
        first7.getTechnicalRecordHash(), first7.getShardConfigurationHash(),
        tree7.certificate(first7.getShardId()), utc7, seal);
    UnicityCertificate certificate8 = new UnicityCertificate(first8.getInputRecord(),
        first8.getTechnicalRecordHash(), first8.getShardConfigurationHash(),
        tree8.certificate(first8.getShardId()), utc8, seal);

    PartitionViewAuthenticator authenticator = authenticator(PartitionViewPolicy.none());
    // control: the view of partition 7 authenticates with its own certificate
    assertEquals(7, authenticator.authenticate(PartitionView.of(7, leaves7), certificate7)
        .getPartitionId());

    // partition 8's leaves, presented as partition 7's view, with partition 8's certificate
    PartitionViewException relabelled = assertThrows(PartitionViewException.class,
        () -> authenticator.authenticate(PartitionView.of(7, leaves8), certificate8));
    assertEquals(Reason.COMMITMENT, relabelled.getReason());
    assertTrue(relabelled.getMessage().contains("partition 8"), relabelled.getMessage());
  }

  /**
   * A session that already runs as many distinct seal checks as it allows reports a resource
   * limit, not a rejection of the view: the documented SealVerificationBusyException, never a
   * PartitionViewException.
   */
  @Test
  void aBusySealSessionIsAResourceLimitNotAViewRejection() throws Exception {
    BlockingTrustBase blocking = BlockingTrustBase.copyOf(AUTHORITY.trustBase());
    PartitionViewAuthenticator limited = new PartitionViewAuthenticator(
        SealVerificationSession.create(blocking, 8, 1), PartitionViewPolicy.none());
    TestChain.Sealed first = TestChain.seal(AUTHORITY, 7, 9, TestChain.fourShards(3, NOW));
    TestChain.Sealed second = TestChain.seal(AUTHORITY, 7, 10, TestChain.fourShards(4, NOW));

    blocking.arm();
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      java.util.concurrent.Future<AuthenticatedPartitionView> held = pool.submit(
          () -> limited.authenticate(first.view, first.certificate("00")));
      for (int i = 0; i < 500 && blocking.entered() == 0; i++) {
        Thread.sleep(10);
      }
      assertEquals(1, blocking.entered(), "the first check is in flight");

      org.unicitylabs.sdk.api.bft.verification.SealVerificationBusyException busy =
          assertThrows(org.unicitylabs.sdk.api.bft.verification.SealVerificationBusyException.class,
              () -> limited.authenticate(second.view, second.certificate("00")));
      assertEquals(1, busy.getMaxInFlight());

      blocking.release();
      assertEquals(7, held.get(10, java.util.concurrent.TimeUnit.SECONDS).getPartitionId());
      // nothing was remembered about the refused one: it authenticates once there is room
      assertEquals(7, limited.authenticate(second.view, second.certificate("00")).getPartitionId());
    } finally {
      blocking.release();
      pool.shutdownNow();
    }
  }
}
