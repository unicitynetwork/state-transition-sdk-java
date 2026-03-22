package org.unicitylabs.sdk.transaction;

import java.util.Objects;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

public class MintTransactionState extends DataHash {

  private static final byte[] MINT_SUFFIX = HexConverter.decode(
      "9e82002c144d7c5796c50f6db50a0c7bbd7f717ae3af6c6c71a3e9eba3022730");

  private MintTransactionState(DataHash hash) {
    super(hash.getAlgorithm(), hash.getData());
  }

  public static MintTransactionState create(TokenId tokenId) {
    Objects.requireNonNull(tokenId, "Token ID cannot be null");

    return new MintTransactionState(
        new DataHasher(HashAlgorithm.SHA256)
            .update(
                CborSerializer.encodeArray(
                    CborSerializer.encodeByteString(tokenId.getBytes()),
                    CborSerializer.encodeByteString(MintTransactionState.MINT_SUFFIX)
                )
            )
            .digest()
    );
  }

}
