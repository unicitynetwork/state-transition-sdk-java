package org.unicitylabs.sdk.mtree.plain;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.mtree.BranchExistsException;
import org.unicitylabs.sdk.mtree.LeafOutOfBoundsException;
import org.unicitylabs.sdk.mtree.MerkleTreePathVerificationResult;
import org.unicitylabs.sdk.util.HexConverter;

public class SparseMerkleTreeTest {

  private final SparseMerkleTreeRootNode root = SparseMerkleTreeRootNode.create(
      new PendingNodeBranch(
          BigInteger.valueOf(0b10),
          new PendingNodeBranch(
              BigInteger.valueOf(0b10),
              new PendingNodeBranch(
                  BigInteger.valueOf(0b100),
                  new PendingLeafBranch(
                      BigInteger.valueOf(0b10000),
                      HexConverter.decode("76616c75653030303030303030")
                  ),
                  new PendingNodeBranch(
                      BigInteger.valueOf(0b1001),
                      new PendingLeafBranch(
                          BigInteger.valueOf(0b10),
                          HexConverter.decode("76616c75653030303130303030")
                      ),
                      new PendingLeafBranch(
                          BigInteger.valueOf(0b11),
                          HexConverter.decode("76616c75653030303130303030")
                      )
                  )
              ),
              new PendingLeafBranch(
                  BigInteger.valueOf(0b11),
                  HexConverter.decode("76616c7565313030")
              )
          ),
          new PendingLeafBranch(
              BigInteger.valueOf(0b1000101),
              HexConverter.decode("76616c756530303031303130")
          )
      ).finalize(HashAlgorithm.SHA256),
      new PendingNodeBranch(
          BigInteger.valueOf(0b11),
          new PendingNodeBranch(
              BigInteger.valueOf(0b1010),
              new PendingLeafBranch(
                  BigInteger.valueOf(0b11110),
                  HexConverter.decode("76616c75653131313030313031")
              ),
              new PendingLeafBranch(
                  BigInteger.valueOf(0b1101),
                  HexConverter.decode("76616c756531303130313031")
              )
          ),
          new PendingNodeBranch(
              BigInteger.valueOf(0b11),
              new PendingLeafBranch(
                  BigInteger.valueOf(0b10),
                  HexConverter.decode("76616c7565303131")
              ),
              new PendingLeafBranch(
                  BigInteger.valueOf(0b1111011),
                  HexConverter.decode("76616c75653131313031313131")
              )
          )
      ).finalize(HashAlgorithm.SHA256),
      HashAlgorithm.SHA256
  );

  @Test
  public void treeShouldBeHalfCalculated() throws Exception {
    SparseMerkleTree smt = new SparseMerkleTree(HashAlgorithm.SHA256);

    smt.addLeaf(BigInteger.valueOf(0b10), new byte[]{1, 2, 3});
    smt.calculateRoot();
    smt.addLeaf(BigInteger.valueOf(0b11), new byte[]{1, 2, 3, 4});

    FinalizedLeafBranch left = new PendingLeafBranch(BigInteger.valueOf(2),
        new byte[]{1, 2, 3}).finalize(HashAlgorithm.SHA256);
    PendingLeafBranch right = new PendingLeafBranch(BigInteger.valueOf(3), new byte[]{1, 2, 3, 4});

    Field leftField = SparseMerkleTree.class.getDeclaredField("left");
    leftField.setAccessible(true);
    Field rightField = SparseMerkleTree.class.getDeclaredField("right");
    rightField.setAccessible(true);

    Assertions.assertEquals(left, leftField.get(smt));
    Assertions.assertEquals(right, rightField.get(smt));
  }

  @Test
  public void shouldVerifyTheTree() throws Exception {
    SparseMerkleTree smt = new SparseMerkleTree(HashAlgorithm.SHA256);
    Map<Integer, String> leaves = Map.ofEntries(
        Map.entry(0b110010000, "value00010000"),
        Map.entry(0b100000000, "value00000000"),
        Map.entry(0b100010000, "value00010000"),
        Map.entry(0b111100101, "value11100101"),
        Map.entry(0b1100, "value100"),
        Map.entry(0b1011, "value011"),
        Map.entry(0b111101111, "value11101111"),
        Map.entry(0b10001010, "value0001010"),
        Map.entry(0b11010101, "value1010101")
    );
    for (Map.Entry<Integer, String> leaf : leaves.entrySet()) {
      smt.addLeaf(BigInteger.valueOf(leaf.getKey()),
          leaf.getValue().getBytes(StandardCharsets.UTF_8));
    }

    Assertions.assertThrows(BranchExistsException.class, () ->
        smt.addLeaf(BigInteger.valueOf(0b10000000), "OnPath".getBytes(StandardCharsets.UTF_8))
    );

    Assertions.assertThrows(LeafOutOfBoundsException.class, () ->
        smt.addLeaf(BigInteger.valueOf(0b1000000000),
            "ThroughLeaf".getBytes(StandardCharsets.UTF_8))
    );

    Assertions.assertEquals(smt.calculateRoot(), this.root);
  }

  @Test
  public void shouldGetWorkingPath() throws Exception {
    SparseMerkleTree smt = new SparseMerkleTree(HashAlgorithm.SHA256);
    Map<Integer, String> leaves = Map.ofEntries(
        Map.entry(0b110010000, "value00010000"),
        Map.entry(0b100000000, "value00000000"),
        Map.entry(0b100010000, "value00010000"),
        Map.entry(0b111100101, "value11100101"),
        Map.entry(0b1100, "value100"),
        Map.entry(0b1011, "value011"),
        Map.entry(0b111101111, "value11101111"),
        Map.entry(0b10001010, "value0001010"),
        Map.entry(0b11010101, "value1010101")
    );
    for (Map.Entry<Integer, String> leaf : leaves.entrySet()) {
      smt.addLeaf(BigInteger.valueOf(leaf.getKey()),
          leaf.getValue().getBytes(StandardCharsets.UTF_8));
    }
    SparseMerkleTreeRootNode root = smt.calculateRoot();

    SparseMerkleTreePath path = root.getPath(BigInteger.valueOf(0b11010));
    MerkleTreePathVerificationResult result = path.verify(BigInteger.valueOf(0b11010));
    Assertions.assertFalse(result.isPathIncluded());
    Assertions.assertTrue(result.isPathValid());
    Assertions.assertFalse(result.isSuccessful());

    path = root.getPath(BigInteger.valueOf(0b110010000));
    result = path.verify(BigInteger.valueOf(0b110010000));
    Assertions.assertTrue(result.isPathIncluded());
    Assertions.assertTrue(result.isPathValid());
    Assertions.assertTrue(result.isSuccessful());

    path = root.getPath(BigInteger.valueOf(0b110010000));
    result = path.verify(BigInteger.valueOf(0b11010));
    Assertions.assertFalse(result.isPathIncluded());
    Assertions.assertTrue(result.isPathValid());
    Assertions.assertFalse(result.isSuccessful());

    path = root.getPath(BigInteger.valueOf(0b111100101));
    result = path.verify(BigInteger.valueOf(0b111100101));
    Assertions.assertTrue(result.isPathIncluded());
    Assertions.assertTrue(result.isPathValid());
    Assertions.assertTrue(result.isSuccessful());

    SparseMerkleTree emptyTree = new SparseMerkleTree(HashAlgorithm.SHA256);
    SparseMerkleTreeRootNode emptyRoot = emptyTree.calculateRoot();
    path = emptyRoot.getPath(BigInteger.valueOf(0b100));
    result = path.verify(BigInteger.valueOf(0b10));
    Assertions.assertFalse(result.isPathIncluded());
    Assertions.assertTrue(result.isPathValid());
    Assertions.assertFalse(result.isSuccessful());
  }
}
