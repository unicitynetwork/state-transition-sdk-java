package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Options of a {@link TrustBaseSession}: resource policy, the executor and the release table. */
public final class TrustBaseSessionOptions {

  private final Executor executor;
  private final TrustBaseBundleLimits limits;
  private final List<ReleasePin> table;

  private TrustBaseSessionOptions(Executor executor, TrustBaseBundleLimits limits,
      List<ReleasePin> table) {
    this.executor = executor;
    this.limits = limits;
    this.table = table;
  }

  /**
   * Options with the default budget and the built-in release table.
   *
   * @param executor where the session's work runs, one task at a time per session: a bounded
   *     executor of the application, not the UI thread. A direct executor ({@code Runnable::run})
   *     runs it on the calling thread
   * @return the options
   */
  public static TrustBaseSessionOptions of(Executor executor) {
    return new TrustBaseSessionOptions(Objects.requireNonNull(executor, "executor cannot be null"),
        ReleasePin.DEFAULT_LIMITS, ReleasePins.ROWS);
  }

  /**
   * Set the budget of every bundle the session loads or saves.
   *
   * @param limits the budget
   * @return the changed options
   */
  public TrustBaseSessionOptions withLimits(TrustBaseBundleLimits limits) {
    return new TrustBaseSessionOptions(this.executor,
        Objects.requireNonNull(limits, "limits cannot be null"), this.table);
  }

  /**
   * Replace the release table. For the release build and its tests, not for applications.
   *
   * @param table the rows
   * @return the changed options
   */
  public TrustBaseSessionOptions withTable(List<ReleasePin> table) {
    return new TrustBaseSessionOptions(this.executor, this.limits,
        List.copyOf(Objects.requireNonNull(table, "table cannot be null")));
  }

  Executor getExecutor() {
    return this.executor;
  }

  /**
   * Get the budget.
   *
   * @return the budget
   */
  public TrustBaseBundleLimits getLimits() {
    return this.limits;
  }

  /**
   * Get the release table.
   *
   * @return the rows
   */
  public List<ReleasePin> getTable() {
    return this.table;
  }
}
