package org.unicitylabs.sdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

class RequestOptionsTest {

  @Test
  void defaultsAddNothing() {
    assertNull(RequestOptions.none().getWaitMillis());
    assertNull(RequestOptions.none().getOperation());
    assertNull(BatchRequestOptions.latest().getWaitMillis());
    assertNull(BatchRequestOptions.latest().getStateRoot());
  }

  @Test
  void waitIsBoundedByTheWire() {
    assertEquals(0L, RequestOptions.none().withWaitMillis(0).getWaitMillis());
    assertEquals(WaitOptions.MAX_WAIT_MILLIS,
        RequestOptions.none().withWaitMillis(WaitOptions.MAX_WAIT_MILLIS).getWaitMillis());
    assertThrows(IllegalArgumentException.class,
        () -> RequestOptions.none().withWaitMillis(WaitOptions.MAX_WAIT_MILLIS + 1));
    assertThrows(IllegalArgumentException.class, () -> RequestOptions.none().withWaitMillis(-1));
  }

  @Test
  void exactRootAndWaitAreMutuallyExclusive() {
    DataHash root = new DataHash(HashAlgorithm.SHA256, new byte[32]);
    BatchRequestOptions exact = BatchRequestOptions.exact(root);
    assertThrows(IllegalArgumentException.class, () -> exact.withWaitMillis(0));
    assertEquals(root, exact.getStateRoot());
    assertEquals(5L, BatchRequestOptions.latest().withWaitMillis(5).getWaitMillis());
  }

  @Test
  void exactRootMustBeSha256() {
    assertThrows(IllegalArgumentException.class, () -> BatchRequestOptions.exact(null));
    assertThrows(IllegalArgumentException.class,
        () -> BatchRequestOptions.exact(new DataHash(HashAlgorithm.SHA384, new byte[48])));
  }

  @Test
  void waitOptionsDefaultToLongPoll() {
    WaitOptions options = WaitOptions.defaults();
    assertEquals(true, options.isLongPoll());
    assertEquals(Duration.ofSeconds(20), options.getWindow());
    assertEquals(Duration.ofSeconds(2), options.getMargin());
    assertEquals(false, options.longPoll(false).isLongPoll());
    assertThrows(IllegalArgumentException.class, () -> options.timeout(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> options.margin(Duration.ofMillis(-1)));
  }

  @Test
  void batchResultCopiesItsInputs() {
    byte[] view = {1, 2, 3};
    InclusionProofBatch batch = new InclusionProofBatch(java.util.Collections.emptyList(), view, 2, 7L);
    view[0] = 9;
    assertEquals(1, batch.getPartitionViewBytes()[0]);
    batch.getPartitionViewBytes()[1] = 9;
    assertEquals(2, batch.getPartitionViewBytes()[1]);
    assertEquals(2, batch.getRequests());
    assertThrows(IllegalArgumentException.class,
        () -> new InclusionProofBatch(java.util.Collections.emptyList(), null, 3, null));
  }
}
