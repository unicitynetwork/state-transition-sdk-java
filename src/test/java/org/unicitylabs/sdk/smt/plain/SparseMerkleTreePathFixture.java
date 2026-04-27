package org.unicitylabs.sdk.smt.plain;

import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

import java.util.List;

public class SparseMerkleTreePathFixture {

  public static SparseMerkleTreePath create() {
    return new SparseMerkleTreePath(
            new DataHasher(HashAlgorithm.SHA256)
                    .update(new byte[]{0})
                    .update(new byte[]{0})
                    .digest(),
            List.of()
    );
  }

}
