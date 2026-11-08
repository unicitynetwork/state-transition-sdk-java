package org.unicitylabs.sdk.api.bft.trustbase;

/** What {@link TrustBaseHistory#append} did with an entry that passed. */
public enum TrustBaseAppendOutcome {
  /** The entry was added. */
  APPENDED,
  /** The entry was already held: nothing changed. */
  ALREADY_HELD
}
