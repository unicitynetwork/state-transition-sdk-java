package org.unicitylabs.sdk.smt;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Common path for two nodes in a merkle tree.
 */
public class CommonPath {

  private final BigInteger path;
  private final int length;

  CommonPath(BigInteger path, int length) {
    this.path = path;
    this.length = length;
  }

  /**
   * Get common path.
   *
   * @return common path
   */
  public BigInteger getPath() {
    return this.path;
  }

  /**
   * Get length of the common path.
   *
   * @return length of the common path
   */
  public int getLength() {
    return this.length;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof CommonPath)) {
      return false;
    }
    CommonPath that = (CommonPath) o;
    return length == that.length && Objects.equals(path, that.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(path, length);
  }

  /**
   * Create common path for two paths.
   *
   * @param path1 first path
   * @param path2 second path
   * @return common path
   */
  public static CommonPath create(BigInteger path1, BigInteger path2) {
    BigInteger path = BigInteger.ONE;
    BigInteger mask = BigInteger.ONE;
    int length = 0;

    while (Objects.equals(path1.and(mask), path2.and(mask)) && path.compareTo(path1) < 0
            && path.compareTo(path2) < 0) {
      mask = mask.shiftLeft(1);
      length += 1;
      path = mask.or(mask.subtract(BigInteger.ONE).and(path1));
    }

    return new CommonPath(path, length);
  }
}
