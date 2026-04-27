package org.unicitylabs.sdk.smt.radix;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

public class FinalizedLeafBranch implements LeafBranch, FinalizedBranch {

    private final BigInteger path;
    private final byte[] key;
    private final byte[] value;
    private final DataHash hash;

    private FinalizedLeafBranch(BigInteger path, byte[] key, byte[] value, DataHash hash) {
        this.path = path;
        this.key = Arrays.copyOf(key, key.length);
        this.value = Arrays.copyOf(value, value.length);
        this.hash = hash;
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
    public DataHash getHash() {
        return this.hash;
    }

    @Override
    public FinalizedLeafBranch finalize(HashAlgorithm hashAlgorithm) {
        return this;
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

    public static FinalizedLeafBranch fromPendingLeaf(
            HashAlgorithm hashAlgorithm,
            PendingLeafBranch leaf
    ) {
        byte[] key = leaf.getKey();
        byte[] value = leaf.getValue();


        DataHash hash = new DataHasher(hashAlgorithm)
                .update(new byte[]{0x00})
                .update(key)
                .update(value)
                .digest();

        return new FinalizedLeafBranch(leaf.getPath(), key, value, hash);
    }
}
