package org.unicitylabs.sdk.api.jsonrpc;

import java.util.Objects;
import org.unicitylabs.sdk.SdkVersion;

/**
 * What an HTTP transport of this SDK sends in the {@value #NAME} header: the default
 * {@code java/<SDK version>}, a caller-chosen value, or nothing. The header is sent on every HTTP
 * call of the transport, including the trust-base GET.
 */
public final class SdkVersionHeader {

  /** The header name. */
  public static final String NAME = "X-SDK-Version";

  private static final SdkVersionHeader DEFAULT = new SdkVersionHeader("java/" + SdkVersion.VERSION);
  private static final SdkVersionHeader DISABLED = new SdkVersionHeader(null);

  private final String value;

  private SdkVersionHeader(String value) {
    this.value = value;
  }

  /**
   * The default: {@code java/<SDK version>}, generated from the Gradle project version.
   *
   * @return the default setting
   */
  public static SdkVersionHeader sdkDefault() {
    return DEFAULT;
  }

  /**
   * No header.
   *
   * @return the disabled setting
   */
  public static SdkVersionHeader disabled() {
    return DISABLED;
  }

  /**
   * A caller-chosen value, for example {@code wallet/7}.
   *
   * @param value header value, one or more printable ASCII characters
   * @return the setting
   * @throws IllegalArgumentException if the value is empty or not printable ASCII
   */
  public static SdkVersionHeader of(String value) {
    Objects.requireNonNull(value, "value cannot be null");
    if (value.isEmpty() || !value.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)) {
      throw new IllegalArgumentException("The SDK version header must be printable ASCII.");
    }
    return new SdkVersionHeader(value);
  }

  /**
   * The header value to send.
   *
   * @return the value, or null when the header is disabled
   */
  public String value() {
    return this.value;
  }

  @Override
  public String toString() {
    return "SdkVersionHeader[" + (this.value == null ? "disabled" : this.value) + "]";
  }
}
