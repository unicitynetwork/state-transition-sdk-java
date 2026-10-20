package org.unicitylabs.sdk.api.bft;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A trust base that holds every quorum check once it is armed, so that a test can keep seal
 * verifications in flight deterministically.
 */
public final class BlockingTrustBase extends RootTrustBase {

  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicInteger entered = new AtomicInteger();
  private volatile boolean armed;

  private BlockingTrustBase(RootTrustBase base) {
    super(base.getVersion(), base.getNetworkId(), base.getEpoch(), base.getEpochStartRound(),
        new ArrayList<>(base.getRootNodes()), base.getQuorumThreshold(), base.getStateHash(),
        base.getChangeRecordHash(), base.getPreviousEntryHash(), base.getSignatures());
  }

  /** A copy of the trust base that can be armed. */
  public static BlockingTrustBase copyOf(RootTrustBase base) {
    return new BlockingTrustBase(base);
  }

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
  public long getQuorumThreshold() {
    if (armed) {
      entered.incrementAndGet();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return super.getQuorumThreshold();
  }
}
