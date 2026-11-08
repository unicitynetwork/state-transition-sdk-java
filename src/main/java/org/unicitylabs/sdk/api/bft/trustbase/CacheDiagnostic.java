package org.unicitylabs.sdk.api.bft.trustbase;

/** A non-fatal observation of open or flush; the application decides how to present it. */
public final class CacheDiagnostic {

  private final CacheRejection rejection;
  private final Throwable cause;

  private CacheDiagnostic(CacheRejection rejection, Throwable cause) {
    this.rejection = rejection;
    this.cause = cause;
  }

  /**
   * The cache was rejected and the embedded history used.
   *
   * @param rejection why
   * @return the diagnostic
   */
  public static CacheDiagnostic rejected(CacheRejection rejection) {
    return new CacheDiagnostic(rejection, null);
  }

  /**
   * The cache could not be read; the embedded history is used.
   *
   * @param cause the failure
   * @return the diagnostic
   */
  public static CacheDiagnostic unavailable(Throwable cause) {
    return new CacheDiagnostic(null, cause);
  }

  /**
   * Tell whether the cache was rejected (as opposed to unreadable).
   *
   * @return true for a rejection
   */
  public boolean isRejection() {
    return this.rejection != null;
  }

  /**
   * Get why the cache was rejected.
   *
   * @return the reason, or null when the cache was unreadable
   */
  public CacheRejection getRejection() {
    return this.rejection;
  }

  /**
   * Get the failure that made the cache unreadable.
   *
   * @return the failure, or null for a rejection
   */
  public Throwable getCause() {
    return this.cause;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof CacheDiagnostic && ((CacheDiagnostic) o).rejection == this.rejection
        && ((CacheDiagnostic) o).cause == this.cause;
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(this.rejection, this.cause);
  }

  @Override
  public String toString() {
    return this.rejection != null ? "CacheRejected:" + this.rejection : "CacheUnavailable";
  }
}
