package org.unicitylabs.sdk.smt.sum;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.smt.MerkleTreePathVerificationResult;
import org.unicitylabs.sdk.util.BigIntegerConverter;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Path in a sparse merkle sum tree.
 */
public class SparseMerkleSumTreePath {

  private final DataHash rootHash;
  private final List<SparseMerkleSumTreePathStep> steps;

  SparseMerkleSumTreePath(
          DataHash rootHash,
          List<SparseMerkleSumTreePathStep> steps
  ) {
    Objects.requireNonNull(rootHash, "root cannot be null");
    Objects.requireNonNull(steps, "steps cannot be null");

    this.rootHash = rootHash;
    this.steps = List.copyOf(steps);
  }

  /**
   * Get root hash.
   *
   * @return root hash
   */
  public DataHash getRootHash() {
    return this.rootHash;
  }

  /**
   * Get steps of the path from leaf to the root.
   *
   * @return steps
   */
  public List<SparseMerkleSumTreePathStep> getSteps() {
    return this.steps;
  }

  /**
   * Verify the path against the given state ID.
   *
   * @param stateId state ID to verify against
   * @return result of the verification
   */
  public MerkleTreePathVerificationResult verify(BigInteger stateId) {
    if (this.steps.isEmpty()) {
      return new MerkleTreePathVerificationResult(false, false);
    }

    SparseMerkleSumTreePathStep step = this.steps.get(0);
    byte[] currentData;
    BigInteger currentPath = step.getPath();
    BigInteger currentSum = step.getValue();
    if (step.getPath().compareTo(BigInteger.ONE) > 0) {
      DataHash hash = new DataHasher(this.rootHash.getAlgorithm())
              .update(
                      CborSerializer.encodeArray(
                              CborSerializer.encodeByteString(BigIntegerConverter.encode(step.getPath())),
                              CborSerializer.encodeNullable(
                                      step.getData().orElse(null),
                                      CborSerializer::encodeByteString
                              ),
                              CborSerializer.encodeByteString(BigIntegerConverter.encode(step.getValue()))
                      )
              )
              .digest();

      currentData = hash.getData();
    } else {
      currentPath = BigInteger.ONE;
      currentData = step.getData().orElse(null);
    }

    SparseMerkleSumTreePathStep previousStep = step;
    for (int i = 1; i < this.steps.size(); i++) {
      step = this.steps.get(i);
      boolean isRight = previousStep.getPath().testBit(0);

      byte[] leftHash = isRight ? step.getData().orElse(null) : currentData;
      byte[] rightHash = isRight ? currentData : step.getData().orElse(null);
      BigInteger leftCounter = isRight ? step.getValue() : currentSum;
      BigInteger rightCounter = isRight ? currentSum : step.getValue();

      DataHash hash = new DataHasher(this.rootHash.getAlgorithm())
              .update(
                      CborSerializer.encodeArray(
                              CborSerializer.encodeByteString(BigIntegerConverter.encode(step.getPath())),
                              CborSerializer.encodeNullable(leftHash, CborSerializer::encodeByteString),
                              CborSerializer.encodeByteString(BigIntegerConverter.encode(leftCounter)),
                              CborSerializer.encodeNullable(rightHash, CborSerializer::encodeByteString),
                              CborSerializer.encodeByteString(BigIntegerConverter.encode(rightCounter))
                      )
              )
              .digest();

      currentData = hash.getData();

      int length = step.getPath().bitLength() - 1;
      if (length < 0) {
        return new MerkleTreePathVerificationResult(false, false);
      }
      currentPath = currentPath.shiftLeft(length)
              .or(step.getPath().and(BigInteger.ONE.shiftLeft(length).subtract(BigInteger.ONE)));
      currentSum = currentSum.add(step.getValue());
      previousStep = step;
    }

    boolean pathValid = currentData != null
            && this.rootHash.equals(new DataHash(this.rootHash.getAlgorithm(), currentData));
    boolean pathIncluded = currentPath.compareTo(stateId) == 0;

    return new MerkleTreePathVerificationResult(pathValid, pathIncluded);
  }

  /**
   * Create path from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return path
   */
  public static SparseMerkleSumTreePath fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes, 2);

    return new SparseMerkleSumTreePath(
            DataHash.fromCbor(data.get(0)),
            CborDeserializer.decodeArray(data.get(1)).stream()
                    .map(SparseMerkleSumTreePathStep::fromCbor)
                    .collect(Collectors.toList())
    );
  }

  /**
   * Serialize path to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
            this.rootHash.toCbor(),
            CborSerializer.encodeArray(
                    this.steps.stream()
                            .map(SparseMerkleSumTreePathStep::toCbor)
                            .toArray(byte[][]::new)
            )
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SparseMerkleSumTreePath)) {
      return false;
    }
    SparseMerkleSumTreePath that = (SparseMerkleSumTreePath) o;
    return Objects.equals(this.rootHash, that.rootHash) && Objects.equals(this.steps, that.steps);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.rootHash, this.steps);
  }

  @Override
  public String toString() {
    return String.format("MerkleTreePath{rootHash=%s, steps=%s}", this.rootHash, this.steps);
  }

  /**
   * Root of the sparse merkle sum tree path.
   */
  public static class Root {

    private final DataHash hash;
    private final BigInteger counter;

    Root(
            DataHash hash,
            BigInteger counter
    ) {
      this.hash = Objects.requireNonNull(hash, "hash cannot be null");
      this.counter = Objects.requireNonNull(counter, "counter cannot be null");
    }

    /**
     * Get hash of the root.
     *
     * @return hash
     */
    public DataHash getHash() {
      return this.hash;
    }

    /**
     * Get the counter of the root.
     *
     * @return counter
     */
    public BigInteger getCounter() {
      return this.counter;
    }

    /**
     * Create root from CBOR bytes.
     *
     * @param bytes CBOR bytes
     * @return root
     */
    public static Root fromCbor(byte[] bytes) {
      List<byte[]> data = CborDeserializer.decodeArray(bytes, 2);

      return new Root(
              DataHash.fromCbor(data.get(0)),
              BigIntegerConverter.decode(CborDeserializer.decodeByteString(data.get(1)))
      );
    }

    /**
     * Serialize root to CBOR bytes.
     *
     * @return CBOR bytes
     */
    public byte[] toCbor() {
      return CborSerializer.encodeArray(
              this.hash.toCbor(),
              CborSerializer.encodeByteString(BigIntegerConverter.encode(this.counter))
      );
    }

    @Override
    public String toString() {
      return String.format("Root{hash=%s, counter=%s}", this.hash, this.counter);
    }
  }
}
