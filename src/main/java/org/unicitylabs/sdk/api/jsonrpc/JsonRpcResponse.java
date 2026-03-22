package org.unicitylabs.sdk.api.jsonrpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.UUID;

/**
 * JSON RPC response structure.
 *
 * @param <T> response type
 */
public class JsonRpcResponse<T> {

  private final String version;
  private final T result;
  private final JsonRpcError error;
  private final UUID id;

  @JsonCreator
  JsonRpcResponse(
      @JsonProperty("jsonrpc") String version,
      @JsonProperty("result") T result,
      @JsonProperty("error") JsonRpcError error,
      @JsonProperty("id") UUID id
  ) {
    if (!"2.0".equals(version)) {
      throw new IllegalArgumentException("Invalid JSON-RPC version: " + version);
    }

    this.version = version;
    this.result = result;
    this.error = error;
    this.id = id;
  }

  /**
   * Get JSON RPC version.
   *
   * @return version
   */
  public String getVersion() {
    return this.version;
  }

  /**
   * Get result if exists.
   *
   * @return result
   */
  public T getResult() {
    return this.result;
  }

  /**
   * Get error if exists.
   *
   * @return error
   */
  public JsonRpcError getError() {
    return this.error;
  }

  /**
   * Get id.
   *
   * @return id
   */
  public UUID getId() {
    return this.id;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof JsonRpcResponse)) {
      return false;
    }
    JsonRpcResponse<?> that = (JsonRpcResponse<?>) o;
    return Objects.equals(this.version, that.version) && Objects.equals(this.result,
        that.result) && Objects.equals(this.error, that.error) && Objects.equals(this.id,
        that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.version, this.result, this.error, this.id);
  }
}