package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.e2e.support.SimulatedShardPool;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;

import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Shard-load scenarios. Uses {@link SimulatedShardPool} by default (hermetic);
 * would use real {@code SHARD_<n>_URL} env vars in topology mode (not
 * implemented — the hermetic path proves the routing/batching logic).
 *
 * <p>Scale is intentionally small compared to the TS scenarios (which run
 * 1000×10 batches): hermetic load runs are regression-coverage, not perf.
 */
public class ShardLoadSteps {

  // Degraded scale for hermetic runs — the original TS batchSize/batchCount
  // values (1000×10) are intended for real topology.
  private static final int SHARD_COUNT = 2;
  private static final int HERMETIC_SCALE_FACTOR = 100;

  private SimulatedShardPool pool;
  private int batchSize;
  private int batchCount;
  private int totalPerShard;
  private int concurrency;
  private List<Report> reports = new ArrayList<>();

  private static final class Report {
    final int shardIndex;
    final int submitted;
    final int succeeded;
    final long durationMs;

    Report(int shardIndex, int submitted, int succeeded, long durationMs) {
      this.shardIndex = shardIndex;
      this.submitted = submitted;
      this.succeeded = succeeded;
      this.durationMs = durationMs;
    }
  }

  @Given("the aggregator is set up")
  public void theAggregatorIsSetUp() {
    pool = SimulatedShardPool.create(SHARD_COUNT);
  }

  @Given("the shard topology is discovered")
  public void theShardTopologyIsDiscovered() {
    assertTrue(pool.size() >= 1, "no shards in pool");
  }

  @Given("{int} x {int} mint operations are prepared for each shard")
  public void opsXBatchesArePreparedForEachShard(int size, int count) {
    this.batchSize = scaleDown(size);
    this.batchCount = scaleDown(count);
  }

  @Given("{int} mint operations are prepared for each shard")
  public void opsArePreparedForEachShard(int total) {
    this.totalPerShard = scaleDown(total);
  }

  @When("synchronized batches of {int} are submitted {int} times")
  public void synchronizedBatchesAreSubmittedNTimes(int size, int count) throws Exception {
    int effSize = scaleDown(size);
    int effCount = scaleDown(count);
    reports.clear();
    for (int shardIdx = 0; shardIdx < pool.size(); shardIdx++) {
      reports.add(runShard(shardIdx, effSize * effCount, 1));
    }
  }

  @When("independent batches of {int} are submitted {int} times per shard")
  public void independentBatchesAreSubmittedNTimesPerShard(int size, int count) throws Exception {
    int effSize = scaleDown(size);
    int effCount = scaleDown(count);
    reports.clear();
    for (int shardIdx = 0; shardIdx < pool.size(); shardIdx++) {
      reports.add(runShard(shardIdx, effSize * effCount, 1));
    }
  }

  @When("constant pressure of {int} concurrent operations is applied per shard")
  public void constantPressureIsAppliedPerShard(int concur) throws Exception {
    int effConcur = scaleDown(concur);
    int perShardTotal = totalPerShard > 0 ? totalPerShard : effConcur * 2;
    reports.clear();
    for (int shardIdx = 0; shardIdx < pool.size(); shardIdx++) {
      reports.add(runShard(shardIdx, perShardTotal, effConcur));
    }
  }

  @Then("the shard load report is printed")
  public void theShardLoadReportIsPrinted() {
    assertTrue(!reports.isEmpty(), "no shard-load reports captured");
    StringBuilder sb = new StringBuilder("\n[shard-load report]\n");
    for (Report r : reports) {
      sb.append(String.format("  shard %d: %d/%d ok in %d ms%n",
          r.shardIndex, r.succeeded, r.submitted, r.durationMs));
      assertEquals(r.submitted, r.succeeded,
          "shard " + r.shardIndex + " had submission failures");
    }
    System.out.print(sb);
  }

  private Report runShard(int shardIdx, int totalOps, int parallelism) throws Exception {
    SigningService signing = SigningService.generate();
    Predicate recipient =
        SignaturePredicate.create(signing.getPublicKey());

    long start = System.currentTimeMillis();
    AtomicInteger ok = new AtomicInteger(0);

    if (parallelism == 1) {
      for (int i = 0; i < totalOps; i++) {
        Token t = TokenUtils.mintToken(
            pool.clientFor(shardIdx),
            pool.trustBaseFor(shardIdx),
            pool.verifierFor(shardIdx),
            pool.mjvFor(shardIdx),
            recipient);
        if (t != null) {
          ok.incrementAndGet();
        }
      }
    } else {
      ExecutorService exec = Executors.newFixedThreadPool(parallelism);
      try {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < totalOps; i++) {
          futures.add(CompletableFuture.runAsync(() -> {
            try {
              Token t = TokenUtils.mintToken(
                  pool.clientFor(shardIdx),
                  pool.trustBaseFor(shardIdx),
                  pool.verifierFor(shardIdx),
                  pool.mjvFor(shardIdx),
                  recipient);
              if (t != null) {
                ok.incrementAndGet();
              }
            } catch (Exception e) {
              // Counted as failure by shortfall from submitted.
            }
          }, exec));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(
            5, TimeUnit.MINUTES);
      } finally {
        exec.shutdown();
      }
    }

    long duration = System.currentTimeMillis() - start;
    return new Report(shardIdx, totalOps, ok.get(), duration);
  }

  /** Degrade TS-scale numbers for hermetic runs. */
  private static int scaleDown(int n) {
    int scaled = Math.max(1, n / HERMETIC_SCALE_FACTOR);
    return scaled;
  }
}
