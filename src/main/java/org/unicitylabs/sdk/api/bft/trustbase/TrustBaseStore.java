package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Where a session keeps its one complete bundle per key. The core ships only
 * {@link MemoryTrustBaseStore}; the application picks a durable backend (the file adapter, a
 * database). {@link #compareAndSwap} must be atomic across every thread and process that shares the
 * key: a plain read-then-write does not qualify.
 *
 * <p>The futures must not depend on the executor of the session that calls them.
 */
public interface TrustBaseStore {

  /** The outcome of a compare-and-swap. */
  enum CasOutcome {
    /** Replaced. */
    WRITTEN,
    /** The stored revision is not the expected one; nothing changed. */
    CHANGED
  }

  /** What a store holds under a key: the complete bundle bytes and the revision they were read at. */
  final class Stored {

    private final byte[] bytes;
    private final String revision;

    /**
     * Create a stored value.
     *
     * @param bytes the stored bundle
     * @param revision the revision of exactly these bytes
     */
    public Stored(byte[] bytes, String revision) {
      this.bytes = bytes;
      this.revision = revision;
    }

    /**
     * Get the stored bytes.
     *
     * @return the bytes
     */
    public byte[] getBytes() {
      return this.bytes;
    }

    /**
     * Get the revision: an opaque token bound to the read contents (a digest suffices). It is not
     * authentication.
     *
     * @return the revision
     */
    public String getRevision() {
      return this.revision;
    }
  }

  /**
   * A coherent snapshot of the key.
   *
   * @param key the key
   * @param maxBytes budget, enforced before the value is allocated
   * @return future with the value, or empty when absent. Fails with a
   *     {@link TrustBaseStoreException} (reason {@code OVERSIZE} when the value exceeds the budget)
   */
  CompletableFuture<Optional<Stored>> read(String key, int maxBytes);

  /**
   * Replace the value only if it still is at {@code expectedRevision}.
   *
   * @param key the key
   * @param expectedRevision the revision read before, or null for absent
   * @param bytes the complete bundle to store
   * @return future with the outcome. Fails with a {@link TrustBaseStoreException} on an I/O failure
   */
  CompletableFuture<CasOutcome> compareAndSwap(String key, String expectedRevision, byte[] bytes);
}
