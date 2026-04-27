package org.unicitylabs.sdk.smt.plain;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.smt.MerkleTreePathVerificationResult;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.BigIntegerConverter;

/**
 * Sparse merkle tree path for selected path.
 */
public class SparseMerkleTreePath {

  private final DataHash rootHash;
  private final List<SparseMerkleTreePathStep> steps;

  SparseMerkleTreePath(DataHash rootHash, List<SparseMerkleTreePathStep> steps) {
    Objects.requireNonNull(rootHash, "rootHash cannot be null");
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
   * Get steps to root.
   *
   * @return steps
   */
  public List<SparseMerkleTreePathStep> getSteps() {
    return this.steps;
  }

  /**
   * Verify merkle tree path against given path.
   *
   * @param stateId path
   * @return MerkleTreePathVerificationResult
   */
  public MerkleTreePathVerificationResult verify(BigInteger stateId) {
    if (this.steps.isEmpty()) {
      return new MerkleTreePathVerificationResult(false, false);
    }

    SparseMerkleTreePathStep step = this.steps.get(0);
    byte[] currentData;
    BigInteger currentPath = step.getPath();
    if (step.getPath().compareTo(BigInteger.ONE) > 0) {
      DataHash hash = new DataHasher(this.rootHash.getAlgorithm())
          .update(
              CborSerializer.encodeArray(
                  CborSerializer.encodeByteString(BigIntegerConverter.encode(step.getPath())),
                  CborSerializer.encodeOptional(
                      step.getData().orElse(null),
                      CborSerializer::encodeByteString
                  )
              )
          )
          .digest();

      currentData = hash.getData();
    } else {
      currentPath = BigInteger.ONE;
      currentData = step.getData().orElse(null);
    }

    SparseMerkleTreePathStep previousStep = step;
    for (int i = 1; i < this.steps.size(); i++) {
      step = this.steps.get(i);
      boolean isRight = previousStep.getPath().testBit(0);

      byte[] left = isRight ? step.getData().orElse(null) : currentData;
      byte[] right = isRight ? currentData : step.getData().orElse(null);

      DataHash hash = new DataHasher(this.rootHash.getAlgorithm())
          .update(
              CborSerializer.encodeArray(
                  CborSerializer.encodeByteString(BigIntegerConverter.encode(step.getPath())),
                  CborSerializer.encodeOptional(left, CborSerializer::encodeByteString),
                  CborSerializer.encodeOptional(right, CborSerializer::encodeByteString)
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
      previousStep = step;
    }

    boolean pathValid = currentData != null
        && this.rootHash.equals(new DataHash(this.rootHash.getAlgorithm(), currentData));
    boolean pathIncluded = currentPath.compareTo(stateId) == 0;

    return new MerkleTreePathVerificationResult(pathValid, pathIncluded);
  }

  /**
   * Create sparse merkle tree path from CBOR bytes.
   *
   * @param bytes CBOR bytes
   * @return path
   */
  public static SparseMerkleTreePath fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);

    return new SparseMerkleTreePath(
        DataHash.fromCbor(data.get(0)),
        CborDeserializer.decodeArray(data.get(1)).stream()
            .map(SparseMerkleTreePathStep::fromCbor)
            .collect(Collectors.toList())
    );
  }

  /**
   * Convert sparse merkle tree path to CBOR bytes.
   *
   * @return CBOR bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        this.rootHash.toCbor(),
        CborSerializer.encodeArray(
            this.steps.stream()
                .map(SparseMerkleTreePathStep::toCbor)
                .toArray(byte[][]::new)
        )
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SparseMerkleTreePath)) {
      return false;
    }
    SparseMerkleTreePath that = (SparseMerkleTreePath) o;
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
}
