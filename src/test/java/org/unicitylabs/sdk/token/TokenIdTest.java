
package org.unicitylabs.sdk.token;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.transaction.TokenId;

import java.math.BigInteger;

class TokenIdTest {

  @Test
  void toBigInt() {
    TokenId tokenId = new TokenId(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
    Assertions.assertEquals(
            new BigInteger("116247956593636886635080929986192315456660021052790183176621769190627866451744"),
            tokenId.toBitString().toBigInteger()
    );
  }
}
