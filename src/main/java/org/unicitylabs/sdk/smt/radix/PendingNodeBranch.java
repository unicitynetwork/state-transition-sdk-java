package org.unicitylabs.sdk.smt.radix;

import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

import java.math.BigInteger;

public class PendingNodeBranch implements NodeBranch {
  private final BigInteger path;
  private final int depth;
  private final Branch left;
  private final Branch right;

  public PendingNodeBranch(BigInteger path, int depth, Branch left, Branch right) {
    this.path = path;
    this.depth = depth;
    this.left = left;
    this.right = right;
  }

  @Override
  public BigInteger getPath() {
    return this.path;
  }

  @Override
  public int getDepth() {
    return this.depth;
  }

  @Override
  public Branch getLeft() {
    return this.left;
  }

  @Override
  public Branch getRight() {
    return this.right;
  }

  @Override
  public FinalizedNodeBranch finalize(HashAlgorithm hashAlgorithm) {
    return FinalizedNodeBranch.fromPendingNode(
            hashAlgorithm,
            this
    );
  }
}
