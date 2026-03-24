package org.unicitylabs.sdk.payment;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TransferTransaction;

public class SplitResult {
  private final TransferTransaction burnTransaction;
  private final Map<TokenId, List<SplitReasonProof>> proofs;

  SplitResult(TransferTransaction burnTransaction, Map<TokenId, List<SplitReasonProof>> proofs) {
    this.burnTransaction = burnTransaction;
    this.proofs = Map.copyOf(
        proofs.entrySet().stream().collect(Collectors.toMap(Entry::getKey, value -> List.copyOf(value.getValue())))
    );
  }

  public TransferTransaction getBurnTransaction() {
    return this.burnTransaction;
  }

  public Map<TokenId, List<SplitReasonProof>> getProofs() {
    return this.proofs;
  }
}
