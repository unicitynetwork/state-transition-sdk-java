package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.Collections;
import java.util.List;

/** The outcome of {@link TrustBaseSession#flush}. A failed save does not undo the verified in-memory history. */
public final class FlushResult {

  private final boolean saved;
  private final boolean wrote;
  private final List<CacheDiagnostic> diagnostics;
  private final Throwable error;

  private FlushResult(boolean saved, boolean wrote, List<CacheDiagnostic> diagnostics,
      Throwable error) {
    this.saved = saved;
    this.wrote = wrote;
    this.diagnostics = Collections.unmodifiableList(diagnostics);
    this.error = error;
  }

  static FlushResult saved(boolean wrote, List<CacheDiagnostic> diagnostics) {
    return new FlushResult(true, wrote, diagnostics, null);
  }

  static FlushResult pending(Throwable error) {
    return new FlushResult(false, false, Collections.emptyList(), error);
  }

  /**
   * Tell whether the store holds the session's complete history.
   *
   * @return true if saved; false if the session stays dirty (and verified) and may retry
   */
  public boolean isSaved() {
    return this.saved;
  }

  /**
   * Tell whether bytes were written.
   *
   * @return false when the store already had everything, or nothing here was unsaved
   */
  public boolean isWrote() {
    return this.wrote;
  }

  /**
   * Get a damaged cache the flush found and replaced, reported before the replacement.
   *
   * @return the diagnostics
   */
  public List<CacheDiagnostic> getDiagnostics() {
    return this.diagnostics;
  }

  /**
   * Get why nothing was saved.
   *
   * @return the failure, or null when saved
   */
  public Throwable getError() {
    return this.error;
  }
}
