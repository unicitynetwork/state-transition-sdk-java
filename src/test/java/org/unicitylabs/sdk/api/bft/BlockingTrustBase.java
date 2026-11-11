package org.unicitylabs.sdk.api.bft;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.api.bft.verification.SealQuorumRule;
import org.unicitylabs.sdk.api.bft.verification.rule.UnicitySealQuorumSignaturesVerificationRule;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * A quorum rule that holds every check once it is armed, so that a test can keep seal verifications
 * in flight deterministically.
 */
public final class BlockingTrustBase implements SealQuorumRule {

  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicInteger entered = new AtomicInteger();
  private volatile boolean armed;

  /** From now on every quorum check waits for {@link #release()}. */
  public void arm() {
    armed = true;
  }

  /** Lets every held check go on, now and later. */
  public void release() {
    release.countDown();
  }

  /** Number of checks that reached the hold. */
  public int entered() {
    return entered.get();
  }

  @Override
  public VerificationResult<VerificationStatus> verify(TrustBaseEntry entry,
      org.unicitylabs.sdk.api.bft.UnicitySeal seal) {
    if (armed) {
      entered.incrementAndGet();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return UnicitySealQuorumSignaturesVerificationRule.verify(entry, seal);
  }
}
