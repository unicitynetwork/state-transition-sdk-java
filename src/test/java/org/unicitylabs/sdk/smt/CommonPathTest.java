package org.unicitylabs.sdk.smt;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

public class CommonPathTest {

  @Test
  public void shouldCalculateCommonPath() {
    Assertions.assertEquals(CommonPath.create(
            BigInteger.valueOf(0b11),
            BigInteger.valueOf(0b111101111)
    ), new CommonPath(BigInteger.valueOf(0b11), 1));
    Assertions.assertEquals(CommonPath.create(
            BigInteger.valueOf(0b111101111),
            BigInteger.valueOf(0b11)
    ), new CommonPath(BigInteger.valueOf(0b11), 1));
    Assertions.assertEquals(CommonPath.create(
            BigInteger.valueOf(0b110010000),
            BigInteger.valueOf(0b100010000)
    ), new CommonPath(BigInteger.valueOf(0b10010000), 7));
  }
}
