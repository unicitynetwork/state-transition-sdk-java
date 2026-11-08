package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.Collections;
import java.util.List;

/**
 * An update that did not end with "current". Entries appended before the failure are held
 * ({@link #getAppended()}): the authenticated prefix is kept.
 */
public class TrustBaseUpdateException extends TrustBaseException {

  /** What ended the update. */
  public enum Failure {
    /** The source list is empty. */
    NO_SOURCE,
    /** The history is empty and no network was given. */
    NO_NETWORK,
    /** No source served the range: every source failed, refused, or served an entry refused. */
    NONE_SERVED,
    /** The update was cancelled. */
    CANCELLED
  }

  /** Why one source did not serve. */
  public enum CauseKind {
    /** The source could not deliver a response. */
    SOURCE,
    /** The response was not a valid page. */
    PAGE,
    /** The source answered with a status other than OK (and not "nothing newer"). */
    STATUS,
    /** An OK page that does not continue the requested range or the frozen snapshot. */
    NOT_CONTINUING,
    /** The source served an entry the history refuses. */
    REFUSED
  }

  /** What one source did. */
  public static final class Cause {
    private final int source;
    private final CauseKind kind;
    private final Throwable error;
    private final TrustBaseStatus status;

    Cause(int source, CauseKind kind, Throwable error, TrustBaseStatus status) {
      this.source = source;
      this.kind = kind;
      this.error = error;
      this.status = status;
    }

    /**
     * Get the index in the source list.
     *
     * @return the index
     */
    public int getSource() {
      return this.source;
    }

    /**
     * Get the kind.
     *
     * @return the kind
     */
    public CauseKind getKind() {
      return this.kind;
    }

    /**
     * Get the failure behind the cause.
     *
     * @return the failure, or null for a status or a page that does not continue
     */
    public Throwable getError() {
      return this.error;
    }

    /**
     * Get the status of a {@link CauseKind#STATUS} cause.
     *
     * @return the status, or null
     */
    public TrustBaseStatus getStatus() {
      return this.status;
    }

    @Override
    public String toString() {
      return "source " + this.source + ": " + this.kind
          + (this.status != null ? " " + this.status : "")
          + (this.error != null ? " (" + this.error + ")" : "");
    }
  }

  private final Failure failure;
  private final List<Cause> causes;
  private final int appended;

  TrustBaseUpdateException(Failure failure, List<Cause> causes, int appended) {
    super("Trust base update: " + describe(failure) + " (" + appended + " appended)"
        + (causes.isEmpty() ? "" : "; " + causes));
    this.failure = failure;
    this.causes = Collections.unmodifiableList(causes);
    this.appended = appended;
    for (Cause cause : causes) {
      if (cause.error != null) {
        this.addSuppressed(cause.error);
      }
    }
  }

  private static String describe(Failure failure) {
    switch (failure) {
      case NO_SOURCE:
        return "no source";
      case NO_NETWORK:
        return "the history is empty and no network was given";
      case CANCELLED:
        return "the update was cancelled";
      default:
        return "no source served the range";
    }
  }

  /**
   * Get what ended the update.
   *
   * @return the failure
   */
  public Failure getFailure() {
    return this.failure;
  }

  /**
   * Get what each source did, in order.
   *
   * @return the causes
   */
  public List<Cause> getCauses() {
    return this.causes;
  }

  /**
   * Get the entries appended before the failure; the authenticated prefix is kept.
   *
   * @return the count
   */
  public int getAppended() {
    return this.appended;
  }
}
