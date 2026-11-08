package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** The reference store: a map in memory, copying on the way in and out, with the same contract as any other. */
public final class MemoryTrustBaseStore implements TrustBaseStore {

  private final Map<String, byte[]> values = new HashMap<>();

  /**
   * Put bytes under a key regardless of the revision (a competing writer, in tests).
   *
   * @param key the key
   * @param bytes the bytes, copied
   */
  public synchronized void put(String key, byte[] bytes) {
    this.values.put(key, Arrays.copyOf(bytes, bytes.length));
  }

  /**
   * Get a copy of the bytes under a key.
   *
   * @param key the key
   * @return the bytes, or null when absent
   */
  public synchronized byte[] get(String key) {
    byte[] held = this.values.get(key);
    return held == null ? null : Arrays.copyOf(held, held.length);
  }

  @Override
  public synchronized CompletableFuture<Optional<Stored>> read(String key, int maxBytes) {
    Objects.requireNonNull(key, "key cannot be null");
    byte[] held = this.values.get(key);
    if (held == null) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    if (held.length > maxBytes) {
      return CompletableFuture.failedFuture(new TrustBaseStoreException(
          TrustBaseStoreException.Reason.OVERSIZE,
          held.length + " bytes exceed the budget of " + maxBytes + "."));
    }
    return CompletableFuture.completedFuture(Optional.of(
        new Stored(Arrays.copyOf(held, held.length), TrustBaseCacheKey.revision(held))));
  }

  @Override
  public synchronized CompletableFuture<CasOutcome> compareAndSwap(String key,
      String expectedRevision, byte[] bytes) {
    Objects.requireNonNull(key, "key cannot be null");
    Objects.requireNonNull(bytes, "bytes cannot be null");
    byte[] held = this.values.get(key);
    String current = held == null ? null : TrustBaseCacheKey.revision(held);
    if (!Objects.equals(current, expectedRevision)) {
      return CompletableFuture.completedFuture(CasOutcome.CHANGED);
    }
    this.values.put(key, Arrays.copyOf(bytes, bytes.length));
    return CompletableFuture.completedFuture(CasOutcome.WRITTEN);
  }
}
