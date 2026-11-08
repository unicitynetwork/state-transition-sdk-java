package org.unicitylabs.sdk.api;

/**
 * Unicity network identifier ({@code α}). Used to scope token ids and other
 * network-bound values so they cannot be replayed across networks.
 *
 * <p>The named networks are the live ones: MAINNET 1, TESTNET2 4 and the LOCAL development network
 * 3. Network 2, the old testnet, was removed and has no constant: 2 is an ordinary unnamed id.
 */
public final class NetworkId {

  public static final NetworkId MAINNET = new NetworkId(1, "MAINNET");
  public static final NetworkId LOCAL = new NetworkId(3, "LOCAL");
  public static final NetworkId TESTNET2 = new NetworkId(4, "TESTNET2");

  private final int id;
  private final String name;

  private NetworkId(int id, String name) {
    this.id = id;
    this.name = name;
  }

  private NetworkId(int id) {
    this(id, null);
  }

  /**
   * Resolve a NetworkId from its raw 16-bit identifier. The whole 16-bit unsigned range
   * {@code [1, 65535]} is valid; a {@code short} carries every bit pattern, so ids at or above
   * {@code 0x8000} are supplied as negative shorts and interpreted as their unsigned value.
   * Returns the registered singleton for known ids; constructs a new (unnamed) instance for any
   * other value.
   *
   * @param id raw 16-bit network identifier
   * @return NetworkId for the given identifier
   */
  public static NetworkId fromId(short id) {
    int value = id & 0xFFFF;
    if (value == 0) {
      throw new IllegalArgumentException("Network identifier cannot be zero.");
    }

    if (value == MAINNET.id) {
      return MAINNET;
    }
    if (value == LOCAL.id) {
      return LOCAL;
    }
    if (value == TESTNET2.id) {
      return TESTNET2;
    }
    return new NetworkId(value);
  }

  /**
   * Get the network identifier as its unsigned 16-bit value in {@code [1, 65535]}. Returned as an
   * {@code int} so ids at or above {@code 0x8000} are not sign-extended when encoded or written as
   * a number.
   *
   * @return unsigned numeric identifier
   */
  public int getId() {
    return this.id;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof NetworkId)) {
      return false;
    }
    return this.id == ((NetworkId) o).id;
  }

  @Override
  public int hashCode() {
    return Integer.hashCode(this.id);
  }

  @Override
  public String toString() {
    return "NetworkId[" + (this.name != null ? this.name : this.id) + "]";
  }
}