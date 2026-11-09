package org.unicitylabs.sdk.api.bft.trustbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseTestChain.Committee;

/**
 * The rules of {@link TrustBaseEntry#verifySuccessor} one at a time, each with a successor that is
 * signed by the predecessor's quorum and wrong in that one respect only. A rule that is removed
 * fails exactly its test; a successor of the right shape passes.
 */
class TrustBaseSuccessorRulesTest {

  private static final int NETWORK = 4;
  private final Committee committee = new Committee(4, 1, 7);
  private final TrustBaseEntry genesis = TrustBaseTestChain.genesis(NETWORK, 1, 10, committee);

  /** A successor built with the public constructor, signed by three of the four members. */
  private TrustBaseEntry successor(int network, long epoch, long startRound, long threshold) {
    Map<String, byte[]> placeholder = Map.of("x", new byte[65]);
    TrustBaseEntry unsigned = new TrustBaseEntry(network, epoch, startRound, committee.members,
        threshold, null, null, genesis.entryId(), placeholder);
    byte[] data = unsigned.sigBytes();
    Map<String, byte[]> signatures = new LinkedHashMap<>();
    for (String signer : TrustBaseTestChain.firstN(committee, 3)) {
      signatures.put(signer, TrustBaseTestChain.sign(committee.secrets.get(signer), data));
    }
    return new TrustBaseEntry(network, epoch, startRound, committee.members, threshold, null,
        null, genesis.entryId(), signatures);
  }

  @Test
  void aSuccessorOfTheRightShapeIsAccepted() {
    successor(NETWORK, 2, 20, committee.threshold()).verifySuccessor(genesis);
  }

  @Test
  void theSuccessorsStructureIsValidatedBeforeItsSignatures() {
    // a threshold of 1 where 3 is required for weight 4, signed by a predecessor quorum
    TrustBaseEntry weak = successor(NETWORK, 2, 20, 1);
    TrustBaseInvalidException direct =
        assertThrows(TrustBaseInvalidException.class, () -> weak.verifySuccessor(genesis));
    assertTrue(direct.getMessage().contains("hreshold"), direct.getMessage());

    TrustBaseHistory history = TrustBaseHistory.create(genesis.entryId());
    history.append(genesis);
    assertThrows(TrustBaseInvalidException.class, () -> history.append(weak));
    assertEquals(1, history.getTip().get().getEpoch(), "nothing was appended");
    // the same entry with the exact threshold is appended: it is the structure that refused it
    history.append(successor(NETWORK, 2, 20, committee.threshold()));
    assertEquals(2, history.getTip().get().getEpoch());
  }

  @Test
  void theNetworkMustBeThePredecessors() {
    // only the network differs: the predecessor id, epoch, start round and signatures are right
    TrustBaseEntry other = successor(NETWORK + 1, 2, 20, committee.threshold());
    TrustBaseChainException e =
        assertThrows(TrustBaseChainException.class, () -> other.verifySuccessor(genesis));
    assertTrue(e.getMessage().startsWith("Network"), e.getMessage());
  }

  @Test
  void theEpochMustBeThePredecessorsPlusOne() {
    // called directly, as the history's gap logic would otherwise answer first
    TrustBaseEntry skipping = successor(NETWORK, 3, 20, committee.threshold());
    TrustBaseChainException e =
        assertThrows(TrustBaseChainException.class, () -> skipping.verifySuccessor(genesis));
    assertTrue(e.getMessage().startsWith("Epoch"), e.getMessage());
  }
}
