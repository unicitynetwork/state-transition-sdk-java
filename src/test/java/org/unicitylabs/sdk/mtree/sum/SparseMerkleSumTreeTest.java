package org.unicitylabs.sdk.mtree.sum;


import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.mtree.MerkleTreePathVerificationResult;
import org.unicitylabs.sdk.mtree.sum.SparseMerkleSumTree.LeafValue;
import java.math.BigInteger;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.util.HexConverter;

class SparseMerkleSumTreeTest {

  @Test
  void shouldAgreeWithSpecExamples() throws Exception {
    SparseMerkleSumTree treeLeftOnly = new SparseMerkleSumTree(HashAlgorithm.SHA256);
    treeLeftOnly.addLeaf(new BigInteger("100", 2), new LeafValue("a".getBytes(), BigInteger.valueOf(1)));
    SparseMerkleSumTreeRootNode rootLeftOnly = treeLeftOnly.calculateRoot();
    Assertions.assertEquals(BigInteger.valueOf(1), rootLeftOnly.getValue());
    Assertions.assertArrayEquals(HexConverter.decode("5822" + "0000" + "34e0cf342d70c0d10e3ba481f72db532ecfd723afa3c25812a4bef61b5198d0b"), rootLeftOnly.getRootHash().toCbor());

    SparseMerkleSumTree treeRightOnly = new SparseMerkleSumTree(HashAlgorithm.SHA256);
    treeRightOnly.addLeaf(new BigInteger("111", 2), new LeafValue("b".getBytes(), BigInteger.valueOf(2)));
    SparseMerkleSumTreeRootNode rootRightOnly = treeRightOnly.calculateRoot();
    Assertions.assertEquals(BigInteger.valueOf(2), rootRightOnly.getValue());
    Assertions.assertArrayEquals(HexConverter.decode("5822" + "0000" + "da47d1cda8dab5159b2bed1ea27c3d24ed990989fac3c62ace05273fea51f958"), rootRightOnly.getRootHash().toCbor());

    SparseMerkleSumTree treeFourLeaves = new SparseMerkleSumTree(HashAlgorithm.SHA256);
    treeFourLeaves.addLeaf(new BigInteger("1000", 2), new LeafValue("a".getBytes(), BigInteger.valueOf(1)));
    treeFourLeaves.addLeaf(new BigInteger("1100", 2), new LeafValue("b".getBytes(), BigInteger.valueOf(2)));
    treeFourLeaves.addLeaf(new BigInteger("1011", 2), new LeafValue("c".getBytes(), BigInteger.valueOf(3)));
    treeFourLeaves.addLeaf(new BigInteger("1111", 2), new LeafValue("d".getBytes(), BigInteger.valueOf(4)));
    SparseMerkleSumTreeRootNode rootFourLeaves = treeFourLeaves.calculateRoot();
    Assertions.assertEquals(BigInteger.valueOf(10), rootFourLeaves.getValue());
    Assertions.assertArrayEquals(HexConverter.decode("5822" + "0000" + "adfefa7c86b18d1216eece9fe0ce82ca58fd8cf482305c3c4e1a0a1361dc9d15"), rootFourLeaves.getRootHash().toCbor());
  }

  @Test
  void shouldBuildTreeWithNumericValues() throws Exception {
    Map<BigInteger, LeafValue> leaves = Map.of(
        new BigInteger("1000", 2), new LeafValue("left-1".getBytes(), BigInteger.valueOf(10)),
        new BigInteger("1001", 2), new LeafValue("right-1".getBytes(), BigInteger.valueOf(20)),
        new BigInteger("1010", 2), new LeafValue("left-2".getBytes(), BigInteger.valueOf(30)),
        new BigInteger("1011", 2), new LeafValue("right-2".getBytes(), BigInteger.valueOf(40))
    );

    SparseMerkleSumTree tree = new SparseMerkleSumTree(HashAlgorithm.SHA256);
    for (Entry<BigInteger, LeafValue> entry : leaves.entrySet()) {
      tree.addLeaf(entry.getKey(), entry.getValue());
    }

    SparseMerkleSumTreeRootNode root = tree.calculateRoot();
    Assertions.assertEquals(BigInteger.valueOf(100), root.getValue());

    for (Entry<BigInteger, LeafValue> entry : leaves.entrySet()) {
      SparseMerkleSumTreePath path = root.getPath(entry.getKey());
      MerkleTreePathVerificationResult verificationResult = path.verify(entry.getKey());
      Assertions.assertTrue(verificationResult.isPathIncluded());
      Assertions.assertTrue(verificationResult.isPathValid());
      Assertions.assertTrue(verificationResult.isSuccessful());

      Assertions.assertEquals(root.getRootHash(), path.getRootHash());
      Assertions.assertArrayEquals(
          entry.getValue().getValue(),
          path.getSteps().get(0).getData().orElse(null)
      );
      Assertions.assertEquals(
          entry.getValue().getCounter(),
          path.getSteps().get(0).getValue()
      );
    }

    tree.addLeaf(new BigInteger("1110", 2), new LeafValue(new byte[32], BigInteger.valueOf(100)));
    root = tree.calculateRoot();
    Assertions.assertEquals(BigInteger.valueOf(200), root.getValue());
  }

  @Test
  void shouldThrowErrorOnNonPositivePathOrSum() {
    SparseMerkleSumTree tree = new SparseMerkleSumTree(HashAlgorithm.SHA256);
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> tree.addLeaf(BigInteger.valueOf(-1),
            new LeafValue(new byte[32], BigInteger.valueOf(100))));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> tree.addLeaf(BigInteger.ONE, new LeafValue(new byte[32], BigInteger.valueOf(-1))));
  }
}
