package org.unicitylabs.sdk.smt.radix;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.util.BitString;
import org.unicitylabs.sdk.util.LongConverter;

import java.math.BigInteger;
import java.util.Arrays;

public class FinalizedNodeBranch implements NodeBranch, FinalizedBranch {
    private final BigInteger path;
    private final int depth;
    private final FinalizedBranch left;
    private final FinalizedBranch right;
    private final DataHash hash;

    private FinalizedNodeBranch(
            BigInteger path,
            int depth,
            FinalizedBranch left,
            FinalizedBranch right,
            DataHash hash
    ) {
        this.path = path;
        this.depth = depth;
        this.left = left;
        this.right = right;
        this.hash = hash;
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
    public FinalizedBranch getLeft() {
        return this.left;
    }

    @Override
    public FinalizedBranch getRight() {
        return this.right;
    }

    @Override
    public DataHash getHash() {
        return this.hash;
    }

    public static FinalizedNodeBranch fromPendingNode(HashAlgorithm hashAlgorithm, PendingNodeBranch node) {
      FinalizedBranch left = node.getLeft() != null ? node.getLeft().finalize(hashAlgorithm) : null;
      FinalizedBranch right = node.getRight() != null ? node.getRight().finalize(hashAlgorithm) : null;

        if (left == null && right == null) {
            return new FinalizedNodeBranch(node.getPath(), node.getDepth(), left, right, new DataHash(HashAlgorithm.SHA256, new byte[32]));
        }

        if (left != null && right == null) {
            return new FinalizedNodeBranch(node.getPath(), node.getDepth(), left, right, left.getHash());
        }

        if (left == null) {
            return new FinalizedNodeBranch(node.getPath(), node.getDepth(), left, right, right.getHash());
        }

        DataHash hash = new DataHasher(hashAlgorithm)
                .update(new byte[]{0x01})
                .update(LongConverter.encode(node.getDepth()))
                .update(left.getHash().getData())
                .update(right.getHash().getData())
                .digest();

        return new FinalizedNodeBranch(node.getPath(), node.getDepth(), left, right, hash);
    }

    @Override
    public FinalizedNodeBranch finalize(HashAlgorithm hashAlgorithm) {
        return this;
    }
}
