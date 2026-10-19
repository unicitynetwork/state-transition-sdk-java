package org.unicitylabs.sdk.api.bft;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer.CborTag;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.UnsignedLongs;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Input record for UnicityCertificate.
 */
public class InputRecord {
  public static final long CBOR_TAG = 39002;
  private static final int VERSION = 1;
  private static final byte[] EMPTY = new byte[0];

  private final long roundNumber;
  private final long epoch;
  private final byte[] previousHash;
  private final byte[] hash;
  private final byte[] summaryValue;
  private final long timestamp;
  private final byte[] blockHash;
  private final long sumOfEarnedFees;
  private final byte[] executedTransactionsHash;

  InputRecord(
          long roundNumber,
          long epoch,
          byte[] previousHash,
          byte[] hash,
          byte[] summaryValue,
          long timestamp,
          byte[] blockHash,
          long sumOfEarnedFees,
          byte[] executedTransactionsHash
  ) {
    this.roundNumber = roundNumber;
    this.epoch = epoch;
    this.previousHash = copy(previousHash);
    this.hash = copy(hash);
    this.summaryValue = copy(summaryValue);
    this.timestamp = timestamp;
    this.blockHash = copy(blockHash);
    this.sumOfEarnedFees = sumOfEarnedFees;
    this.executedTransactionsHash = copy(executedTransactionsHash);
  }

  private static byte[] copy(byte[] value) {
    return value != null ? Arrays.copyOf(value, value.length) : null;
  }

  public int getVersion() {
    return InputRecord.VERSION;
  }

  /**
   * Get round number as unsigned 64-bit bits: a value of 2^63 or above reads as a negative
   * {@code long}. See {@link #getRoundNumberUnsigned()} and {@link #getRoundNumberDecimal()}.
   *
   * @return round number bits
   */
  public long getRoundNumber() {
    return this.roundNumber;
  }

  /**
   * Get round number as a non-negative integer.
   *
   * @return round number
   */
  public BigInteger getRoundNumberUnsigned() {
    return UnsignedLongs.toBigInteger(this.roundNumber);
  }

  /**
   * Get round number in unsigned decimal.
   *
   * @return round number
   */
  public String getRoundNumberDecimal() {
    return UnsignedLongs.toDecimal(this.roundNumber);
  }

  /**
   * Get epoch as unsigned 64-bit bits. See {@link #getEpochUnsigned()}.
   *
   * @return epoch bits
   */
  public long getEpoch() {
    return this.epoch;
  }

  /**
   * Get epoch as a non-negative integer.
   *
   * @return epoch
   */
  public BigInteger getEpochUnsigned() {
    return UnsignedLongs.toBigInteger(this.epoch);
  }

  /**
   * Get epoch in unsigned decimal.
   *
   * @return epoch
   */
  public String getEpochDecimal() {
    return UnsignedLongs.toDecimal(this.epoch);
  }

  /**
   * Get previous hash.
   *
   * @return previous hash or null if not set
   */
  public byte[] getPreviousHash() {
    return this.previousHash != null ? Arrays.copyOf(this.previousHash, this.previousHash.length)
            : null;
  }

  /**
   * Get hash, the root of the state tree. CBOR {@code null} (the certified empty tree) is
   * {@code null} here and distinct from an empty byte string; a {@code null} root means the empty
   * tree, never "no proof".
   *
   * @return hash, or null for the certified empty tree
   */
  public byte[] getHash() {
    return copy(this.hash);
  }

  /**
   * Get summary value. CBOR {@code null} is {@code null} here and distinct from an empty byte
   * string.
   *
   * @return summary value, or null if not set
   */
  public byte[] getSummaryValue() {
    return copy(this.summaryValue);
  }

  /**
   * Get timestamp as unsigned 64-bit bits (Unix seconds). See {@link #getTimestampUnsigned()}.
   *
   * @return timestamp bits
   */
  public long getTimestamp() {
    return this.timestamp;
  }

  /**
   * Get timestamp as a non-negative integer.
   *
   * @return timestamp
   */
  public BigInteger getTimestampUnsigned() {
    return UnsignedLongs.toBigInteger(this.timestamp);
  }

  /**
   * Get timestamp in unsigned decimal.
   *
   * @return timestamp
   */
  public String getTimestampDecimal() {
    return UnsignedLongs.toDecimal(this.timestamp);
  }

  /**
   * Get block hash.
   *
   * @return block hash or null if not set
   */
  public byte[] getBlockHash() {
    return this.blockHash != null ? Arrays.copyOf(this.blockHash, this.blockHash.length) : null;
  }

  /**
   * Get sum of earned fees as unsigned 64-bit bits. See {@link #getSumOfEarnedFeesUnsigned()}.
   *
   * @return sum of earned fees bits
   */
  public long getSumOfEarnedFees() {
    return this.sumOfEarnedFees;
  }

  /**
   * Get sum of earned fees as a non-negative integer.
   *
   * @return sum of earned fees
   */
  public BigInteger getSumOfEarnedFeesUnsigned() {
    return UnsignedLongs.toBigInteger(this.sumOfEarnedFees);
  }

  /**
   * Get sum of earned fees in unsigned decimal.
   *
   * @return sum of earned fees
   */
  public String getSumOfEarnedFeesDecimal() {
    return UnsignedLongs.toDecimal(this.sumOfEarnedFees);
  }

  /**
   * Get executed transactions hash.
   *
   * @return executed transactions hash or null if not set
   */
  public byte[] getExecutedTransactionsHash() {
    return this.executedTransactionsHash != null ? Arrays.copyOf(this.executedTransactionsHash,
            this.executedTransactionsHash.length) : null;
  }

  /**
   * Native structural validity of the record (bft-go-base {@code InputRecord.IsValid}), which is
   * conditional: from round 1 the summary value must not be null and the timestamp must be
   * assigned; if the state hash did not change the block hash must be nil (null or empty), and if
   * it changed the block hash must not be nil. A null field alone is not malformed; null and empty
   * compare equal here exactly as in the reference ({@code bytes.Equal}).
   *
   * @return description of the violated rule, or null if the record is structurally valid
   */
  public String structureFailure() {
    if (this.roundNumber != 0) {
      if (this.summaryValue == null) {
        return "The summary value is nil.";
      }
      if (this.timestamp == 0) {
        return "The timestamp is unassigned.";
      }
    }
    boolean sameStateHash = Arrays.equals(
            this.previousHash != null ? this.previousHash : EMPTY,
            this.hash != null ? this.hash : EMPTY);
    boolean nilBlockHash = this.blockHash == null || this.blockHash.length == 0;
    if (sameStateHash && !nilBlockHash) {
      return "The state hash did not change but the block hash is not nil.";
    }
    if (!sameStateHash && nilBlockHash) {
      return "The block hash is nil but the state hash changed.";
    }
    return null;
  }

  /**
   * Deserialize InputRecord from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return input record
   */
  public static InputRecord fromCbor(byte[] bytes) {
    CborTag tag = CborDeserializer.decodeTag(bytes);
    if (tag.getTag() != InputRecord.CBOR_TAG) {
      throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
    }
    List<byte[]> data = CborDeserializer.decodeArray(tag.getData(), 10);

    int version = CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt();
    if (version != InputRecord.VERSION) {
      throw new CborSerializationException(String.format("Unsupported version: %s", version));
    }

    return new InputRecord(
            CborDeserializer.decodeUnsignedInteger(data.get(1)).asLong(),
            CborDeserializer.decodeUnsignedInteger(data.get(2)).asLong(),
            CborDeserializer.decodeNullable(data.get(3), CborDeserializer::decodeByteString),
            CborDeserializer.decodeNullable(data.get(4), CborDeserializer::decodeByteString),
            CborDeserializer.decodeNullable(data.get(5), CborDeserializer::decodeByteString),
            CborDeserializer.decodeUnsignedInteger(data.get(6)).asLong(),
            CborDeserializer.decodeNullable(data.get(7), CborDeserializer::decodeByteString),
            CborDeserializer.decodeUnsignedInteger(data.get(8)).asLong(),
            CborDeserializer.decodeNullable(data.get(9), CborDeserializer::decodeByteString)
    );
  }

  /**
   * Serialize InputRecord to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeTag(
            InputRecord.CBOR_TAG,
            CborSerializer.encodeArray(
                    CborSerializer.encodeUnsignedInteger(InputRecord.VERSION),
                    CborSerializer.encodeUnsignedInteger(this.roundNumber),
                    CborSerializer.encodeUnsignedInteger(this.epoch),
                    CborSerializer.encodeNullable(this.previousHash, CborSerializer::encodeByteString),
                    CborSerializer.encodeNullable(this.hash, CborSerializer::encodeByteString),
                    CborSerializer.encodeNullable(this.summaryValue,
                            CborSerializer::encodeByteString),
                    CborSerializer.encodeUnsignedInteger(this.timestamp),
                    CborSerializer.encodeNullable(this.blockHash, CborSerializer::encodeByteString),
                    CborSerializer.encodeUnsignedInteger(this.sumOfEarnedFees),
                    CborSerializer.encodeNullable(this.executedTransactionsHash,
                            CborSerializer::encodeByteString)
            ));
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof InputRecord)) {
      return false;
    }
    InputRecord that = (InputRecord) o;
    return Objects.equals(this.roundNumber, that.roundNumber)
            && Objects.equals(this.epoch, that.epoch)
            && Objects.deepEquals(this.previousHash, that.previousHash)
            && Objects.deepEquals(this.hash, that.hash)
            && Objects.deepEquals(this.summaryValue, that.summaryValue)
            && Objects.equals(this.timestamp, that.timestamp)
            && Objects.deepEquals(this.blockHash, that.blockHash)
            && Objects.equals(this.sumOfEarnedFees, that.sumOfEarnedFees)
            && Objects.deepEquals(this.executedTransactionsHash, that.executedTransactionsHash);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.roundNumber, this.epoch,
            Arrays.hashCode(this.previousHash),
            Arrays.hashCode(this.hash), Arrays.hashCode(this.summaryValue), this.timestamp,
            Arrays.hashCode(this.blockHash),
            this.sumOfEarnedFees, Arrays.hashCode(this.executedTransactionsHash));
  }

  @Override
  public String toString() {
    return String.format("InputRecord{roundNumber=%s, epoch=%s, previousHash=%s, "
                    + "hash=%s, summaryValue=%s, timestamp=%s, blockHash=%s, sumOfEarnedFees=%s, "
                    + "executedTransactionsHash=%s}",
            this.roundNumber,
            this.epoch,
            this.previousHash != null ? HexConverter.encode(this.previousHash) : null,
            this.hash != null ? HexConverter.encode(this.hash) : null,
            this.summaryValue != null ? HexConverter.encode(this.summaryValue) : null,
            this.timestamp,
            this.blockHash != null ? HexConverter.encode(this.blockHash) : null,
            this.sumOfEarnedFees,
            this.executedTransactionsHash != null ? HexConverter.encode(this.executedTransactionsHash)
                    : null
    );
  }
}
