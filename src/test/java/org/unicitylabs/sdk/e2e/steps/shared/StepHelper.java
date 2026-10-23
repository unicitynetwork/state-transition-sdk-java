package org.unicitylabs.sdk.e2e.steps.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.InclusionProofUtils;

/**
 * Thin container for stateless helpers shared across step classes. Put logic here only if it does
 * not need scenario state — anything scenario-scoped belongs on the injected {@code TestContext}.
 */
public final class StepHelper {

  private StepHelper() {}

  /**
   * Asserts that a double-spend / re-spend attempt (a NEW transaction spending an already-finalized
   * state) is rejected without ever finalizing into a valid token.
   *
   * <p>This encodes the canonical v2 aggregator contract (aggregator-go async-submit, see
   * state-transition-sdk-java#67): the finalized-duplicate lookup at submit time was removed, so the
   * submit layer returns {@code SUCCESS} and the double-spend is caught one layer later, at
   * inclusion-proof verification, with {@code TRANSACTION_HASH_MISMATCH} (the committed leaf carries
   * the FIRST transaction's hash). A legacy/strict aggregator that still rejects at submit with
   * {@code STATE_ID_EXISTS} is also accepted — either way the invariant "the re-spend never yields a
   * valid token" holds, so this assertion is correct on every aggregator build and topology.
   *
   * @param context        scenario context (client, trust base, predicate verifier)
   * @param respendTx      the re-spend transaction that must not finalize
   * @param submitResponse the certification response from submitting {@code respendTx}
   */
  public static void assertRespendRejected(
      TestContext context, Transaction respendTx, CertificationResponse submitResponse) {
    assertNotNull(submitResponse, "no submit response captured for the re-spend");
    assertNotNull(respendTx, "no re-spend transaction captured");

    String status = submitResponse.getStatus().name();

    // Legacy/strict aggregator: rejected at submit. Nothing more to verify.
    if ("STATE_ID_EXISTS".equals(status)) {
      return;
    }

    // Canonical v2: accepted at submit, must be rejected at proof verification.
    assertEquals(
        "SUCCESS",
        status,
        "re-spend submit must be SUCCESS (v2 async) or STATE_ID_EXISTS (legacy), but was: " + status);
    try {
      InclusionProofUtils.waitInclusionProof(
              context.getClient(),
              context.getTrustBase(),
              context.getPredicateVerifier(),
              respendTx)
          .get();
      fail("expected the re-spend to be rejected at inclusion proof with TRANSACTION_HASH_MISMATCH");
    } catch (Exception e) {
      assertChainMentions(e, "TRANSACTION_HASH_MISMATCH");
    }
  }

  /** Walks the cause chain asserting {@code marker} appears in some message. */
  private static void assertChainMentions(Throwable e, String marker) {
    Throwable t = e;
    while (t != null) {
      if (t.getMessage() != null && t.getMessage().contains(marker)) {
        return;
      }
      t = t.getCause();
    }
    fail("expected status '" + marker + "' in exception chain but got: " + e);
  }
}
