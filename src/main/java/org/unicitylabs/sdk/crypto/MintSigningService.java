package org.unicitylabs.sdk.crypto;

import java.util.Objects;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.util.HexConverter;

public class MintSigningService {

  private static final byte[] MINTER_SECRET = HexConverter.decode(
      "495f414d5f554e4956455253414c5f4d494e5445525f464f525f");

  public static SigningService create(TokenId tokenId) {
    Objects.requireNonNull(tokenId, "Token ID cannot be null");

    return new SigningService(
        new DataHasher(HashAlgorithm.SHA256)
            .update(CborSerializer.encodeArray(
                CborSerializer.encodeByteString(MintSigningService.MINTER_SECRET),
                tokenId.toCbor()))
            .digest()
            .getData()
    );
  }

}
