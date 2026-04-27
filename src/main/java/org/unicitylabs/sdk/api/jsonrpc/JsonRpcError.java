package org.unicitylabs.sdk.api.jsonrpc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON RPC response error.
 */
public class JsonRpcError {

  private final int code;
  private final String message;

  @JsonCreator
  JsonRpcError(
          @JsonProperty("code") int code,
          @JsonProperty("message") String message
  ) {
    this.code = code;
    this.message = message;
  }

  /**
   * Get error code.
   *
   * @return error code
   */
  public int getCode() {
    return code;
  }

  /**
   * Get error message.
   *
   * @return error message
   */
  public String getMessage() {
    return message;
  }
}