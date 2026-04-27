package org.unicitylabs.sdk.payment;

import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TransferTransaction;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Result of token split generation containing burn transaction and per-token proofs.
 */
public class SplitResult {

  private final TransferTransaction burnTransaction;
  private final Map<TokenId, List<SplitReasonProof>> proofs;

  SplitResult(TransferTransaction burnTransaction, Map<TokenId, List<SplitReasonProof>> proofs) {
    this.burnTransaction = burnTransaction;
    this.proofs = Map.copyOf(
            proofs.entrySet().stream()
                    .collect(
                            Collectors.toMap(Entry::getKey, value -> List.copyOf(value.getValue()))
                    )
    );
  }

  /**
   * Get the burn transaction that anchors split proofs.
   *
   * @return burn transaction
   */
  public TransferTransaction getBurnTransaction() {
    return this.burnTransaction;
  }

  /**
   * Get proofs grouped by resulting token id.
   *
   * @return split proofs map
   */
  public Map<TokenId, List<SplitReasonProof>> getProofs() {
    return this.proofs;
  }
}
