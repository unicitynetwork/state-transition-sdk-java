package org.unicitylabs.sdk.api.jsonrpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * JSON RPC request.
 */
public class JsonRpcRequest {

  private final UUID id;
  private final String method;
  private final Object params;

  /**
   * Create JSON RPC request.
   *
   * @param method request method
   * @param params request parameters
   */
  public JsonRpcRequest(String method, Object params) {
    this(UUID.randomUUID(), method, params);
  }

  /**
   * Create JSON RPC request.
   *
   * @param id     request id
   * @param method request method
   * @param params request error
   */
  @JsonCreator
  public JsonRpcRequest(
          @JsonProperty("id") UUID id,
          @JsonProperty("method") String method,
          @JsonProperty("params") Object params
  ) {
    this.id = id;
    this.method = method;
    this.params = params;
  }

  /**
   * Get request ID.
   *
   * @return id
   */
  public UUID getId() {
    return this.id;
  }

  /**
   * Get request version.
   *
   * @return version
   */
  @JsonGetter("jsonrpc")
  public String getVersion() {
    return "2.0";
  }

  /**
   * Get request method.
   *
   * @return method
   */
  public String getMethod() {
    return this.method;
  }

  /**
   * Get request parameters.
   *
   * @return parameters
   */
  public Object getParams() {
    return this.params;
  }
}
