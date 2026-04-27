package org.unicitylabs.sdk.smt.radix;

import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

public class PendingLeafBranch  implements LeafBranch {
  private final BigInteger path;
  private final byte[] key;
  private final byte[] value;

  public PendingLeafBranch(BigInteger path, byte[] key, byte[] value) {
    this.path = path;
    this.key = Arrays.copyOf(key, key.length);
    this.value = Arrays.copyOf(value, value.length);
  }

  @Override
  public BigInteger getPath() {
    return this.path;
  }

  @Override
  public byte[] getKey() {
    return Arrays.copyOf(this.key, this.key.length);
  }

  @Override
  public byte[] getValue() {
    return Arrays.copyOf(this.value, this.value.length);
  }

  @Override
  public FinalizedLeafBranch finalize(HashAlgorithm hashAlgorithm) {
    return FinalizedLeafBranch.fromPendingLeaf(hashAlgorithm, this);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof PendingLeafBranch)) {
      return false;
    }

    PendingLeafBranch that = (PendingLeafBranch) o;
    return Objects.equals(this.path, that.path) && Objects.deepEquals(this.value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.path, Arrays.hashCode(this.value));
  }
}
