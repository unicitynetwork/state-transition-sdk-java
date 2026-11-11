package org.unicitylabs.sdk.api.bft.trustbase;

import java.util.concurrent.CompletableFuture;

/**
 * Where newer Unicity Trust Base entries are fetched from: an aggregator's {@code GET
 * /api/v1/trustbases}, a mirror, a test double. The transport is not ruled; nothing above this
 * interface depends on it.
 *
 * <p>A source is untrusted: it returns the encoded GetTrustBase response (tag 39015) and the caller
 * decodes it with the page limits and appends every entry through the history's {@code append},
 * which authenticates it from the latest held entry. An HTTP success or an advertised tip never
 * authenticates anything.
 */
public interface TrustBaseSource {

  /**
   * Fetch one page.
   *
   * <p>Cancelling the returned future cancels the request.
   *
   * @param request the request
   * @return future with the encoded response (tag 39015), whatever its status
   */
  CompletableFuture<byte[]> getTrustBasePage(TrustBaseRequest request);
}
