package org.unicitylabs.sdk.transaction.verification;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.StateId;
import org.unicitylabs.sdk.api.bft.ShardTreeCertificate;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

public class ShardIdMatchesStateIdRuleTest {

  /** 32 bytes of 0xAB — a valid SHA-256-shaped state id. */
  private static final byte[] STATE_ID_BYTES =
          HexConverter.decode("ABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABAB");

  @Test
  public void verifyFailsWhenShardTreeCertificateIsNull() {
    StateId stateId = StateId.fromCbor(CborSerializer.encodeByteString(STATE_ID_BYTES));

    VerificationResult<VerificationStatus> result =
            ShardIdMatchesStateIdRule.verify(stateId, null);

    Assertions.assertEquals(VerificationStatus.FAIL, result.getStatus());
    Assertions.assertEquals("Shard tree certificate is missing.", result.getMessage());
  }

  @Test
  public void verifyFailsWhenStateIdIsNull() {
    // Empty shard id (length 0).
    ShardTreeCertificate certificate = ShardTreeCertificate.fromCbor(
            HexConverter.decode("d9985b8301418080"));

    VerificationResult<VerificationStatus> result =
            ShardIdMatchesStateIdRule.verify(null, certificate);

    Assertions.assertEquals(VerificationStatus.FAIL, result.getStatus());
    Assertions.assertEquals("State ID is missing.", result.getMessage());
  }

  @Test
  public void verifyPassesWhenShardIdIsEmpty() {
    StateId stateId = StateId.fromCbor(CborSerializer.encodeByteString(STATE_ID_BYTES));
    ShardTreeCertificate certificate = ShardTreeCertificate.fromCbor(
            HexConverter.decode("d9985b8301418080"));

    VerificationResult<VerificationStatus> result =
            ShardIdMatchesStateIdRule.verify(stateId, certificate);

    Assertions.assertEquals(VerificationStatus.OK, result.getStatus());
  }

  @Test
  public void verifyPassesWhenShardIdIsPrefixOfStateId() {
    // Shard id of length 8 with bits=[0xAB].
    StateId stateId = StateId.fromCbor(CborSerializer.encodeByteString(STATE_ID_BYTES));
    ShardTreeCertificate certificate = ShardTreeCertificate.fromCbor(
            HexConverter.decode("d9985b830142ab8080"));

    VerificationResult<VerificationStatus> result =
            ShardIdMatchesStateIdRule.verify(stateId, certificate);

    Assertions.assertEquals(VerificationStatus.OK, result.getStatus());
  }

  @Test
  public void verifyFailsWhenShardIdIsNotPrefixOfStateId() {
    // Shard id of length 8 with bits=[0x12] — does not match.
    StateId stateId = StateId.fromCbor(CborSerializer.encodeByteString(STATE_ID_BYTES));
    ShardTreeCertificate certificate = ShardTreeCertificate.fromCbor(
            HexConverter.decode("d9985b830142128080"));

    VerificationResult<VerificationStatus> result =
            ShardIdMatchesStateIdRule.verify(stateId, certificate);

    Assertions.assertEquals(VerificationStatus.FAIL, result.getStatus());
  }

  @Test
  public void verifyFailsWhenStateIdIsShorterThanShardId() {
    // SHA-256 state id is 32 bytes (256 bits). Use a 264-bit shard id (33 full bytes 0xAB +
    // 0x80 end marker), so the state id has fewer bits than the shard id requires.
    StateId stateId = StateId.fromCbor(CborSerializer.encodeByteString(STATE_ID_BYTES));
    ShardTreeCertificate certificate = ShardTreeCertificate.fromCbor(
            HexConverter.decode(
                    "d9985b83015822ababababababababababababababababababababababababababababababababab8080"));

    VerificationResult<VerificationStatus> result =
            ShardIdMatchesStateIdRule.verify(stateId, certificate);

    Assertions.assertEquals(VerificationStatus.FAIL, result.getStatus());
  }
}
