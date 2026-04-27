package org.unicitylabs.sdk.smt.plain;

import java.util.List;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

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
