package org.unicitylabs.sdk.smt.plain;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.BigIntegerConverter;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/**
 * Finalized leaf branch in a sparse merkle tree.
 */
class FinalizedLeafBranch implements LeafBranch, FinalizedBranch {

  private final BigInteger path;
  private final byte[] value;
  private final DataHash hash;

  private FinalizedLeafBranch(BigInteger path, byte[] value, DataHash hash) {
    this.path = path;
    this.value = Arrays.copyOf(value, value.length);
    this.hash = hash;
  }

  /**
   * Create a finalized leaf branch.
   *
   * @param path          path of the branch
   * @param value         value stored in the leaf
   * @param hashAlgorithm hash algorithm to use
   * @return finalized leaf branch
   */
  public static FinalizedLeafBranch create(
          BigInteger path,
          byte[] value,
          HashAlgorithm hashAlgorithm
  ) {
    DataHash hash = new DataHasher(hashAlgorithm)
            .update(
                    CborSerializer.encodeArray(
                            CborSerializer.encodeByteString(BigIntegerConverter.encode(path)),
                            CborSerializer.encodeByteString(value)
                    )
            )
            .digest();

    return new FinalizedLeafBranch(path, value, hash);
  }

  @Override
  public BigInteger getPath() {
    return this.path;
  }

  @Override
  public byte[] getValue() {
    return Arrays.copyOf(this.value, this.value.length);
  }

  @Override
  public DataHash getHash() {
    return this.hash;
  }

  @Override
  public FinalizedLeafBranch finalize(HashAlgorithm hashAlgorithm) {
    return this; // Already finalized
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof FinalizedLeafBranch)) {
      return false;
    }
    FinalizedLeafBranch that = (FinalizedLeafBranch) o;
    return Objects.equals(this.path, that.path) && Arrays.equals(this.value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.path, Arrays.hashCode(this.value));
  }
}
