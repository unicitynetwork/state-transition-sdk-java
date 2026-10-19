package org.unicitylabs.sdk.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of one batch call: proofs of all requested states, in request order, served from one
 * finalized local view of the shard. A decoded batch is not verified proof material, and the wait
 * echo is unauthenticated timing metadata, never evidence.
 */
public final class InclusionProofBatch {

  private final List<InclusionProofResponse> proofs;
  private final byte[] partitionView;
  private final int requests;
  private final Long waitedMillis;

  /**
   * Creates a batch.
   *
   * @param proofs decoded proof results, one per requested state, in request order
   * @param partitionView canonical CBOR of the partition view as the service sent it, present for
   *     latest requests only; null otherwise
   * @param requests HTTP requests the call took: 1, or 2 after the one retry without a wait
   * @param waitedMillis the aggregator's echo of the time it waited, null if it sent none
   * @throws IllegalArgumentException if {@code requests} is not 1 or 2
   */
  public InclusionProofBatch(List<InclusionProofResponse> proofs, byte[] partitionView, int requests,
      Long waitedMillis) {
    Objects.requireNonNull(proofs, "proofs cannot be null");
    if (requests != 1 && requests != 2) {
      throw new IllegalArgumentException("A call takes 1 or 2 requests.");
    }
    this.proofs = Collections.unmodifiableList(new ArrayList<>(proofs));
    this.partitionView = partitionView == null ? null : Arrays.copyOf(partitionView, partitionView.length);
    this.requests = requests;
    this.waitedMillis = waitedMillis;
  }

  /**
   * Proofs in request order.
   *
   * @return unmodifiable proof list
   */
  public List<InclusionProofResponse> getProofs() {
    return proofs;
  }

  /**
   * Partition view bytes, exactly as the service sent them.
   *
   * @return copy of the bytes, null if the response carried none
   */
  public byte[] getPartitionViewBytes() {
    return partitionView == null ? null : Arrays.copyOf(partitionView, partitionView.length);
  }

  /**
   * HTTP requests the call took.
   *
   * @return 1, or 2 when the aggregator did not know the wait parameter
   */
  public int getRequests() {
    return requests;
  }

  /**
   * The aggregator's unauthenticated echo of the time it waited.
   *
   * @return milliseconds, null if none was asked for or sent
   */
  public Long getWaitedMillis() {
    return waitedMillis;
  }
}
