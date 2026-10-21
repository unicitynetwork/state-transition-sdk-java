package org.unicitylabs.sdk.api.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Error object of a JSON-RPC response: the service's own code, message and optional data.
 *
 * <p>It is a {@link JsonRpcNetworkException} whose status is the JSON-RPC code, as such errors have
 * always been reported, so existing handlers keep working. An error that carries the id of the
 * request has {@link #isIdMatched()} set; a server that could not read the id answers with a null
 * id, and such an error answers nothing of ours.
 */
public class JsonRpcDataException extends JsonRpcNetworkException {

  private final transient JsonNode data;
  private final boolean idMatched;

  /**
   * Creates the exception.
   *
   * @param code JSON-RPC error code
   * @param message error message
   * @param data error data, null if none
   * @param idMatched true if the response carried the id of the request
   */
  public JsonRpcDataException(int code, String message, JsonNode data, boolean idMatched) {
    this(code, message, data, idMatched, null);
  }

  /**
   * Creates the exception with the server's retry hint.
   *
   * @param code JSON-RPC error code
   * @param message error message
   * @param data error data, null if none
   * @param idMatched true if the response carried the id of the request
   * @param retryAfterSeconds the {@code Retry-After} header of the HTTP response in seconds, null
   *     if there was none
   */
  public JsonRpcDataException(int code, String message, JsonNode data, boolean idMatched,
      Long retryAfterSeconds) {
    super(code, message, null, retryAfterSeconds);
    this.data = data;
    this.idMatched = idMatched;
  }

  /**
   * JSON-RPC error code.
   *
   * @return code
   */
  public int getCode() {
    return getStatus();
  }

  /**
   * Error data.
   *
   * @return data, null if the error had none
   */
  public JsonNode getData() {
    return data;
  }

  /**
   * Tells if the response carried the id of the request.
   *
   * @return true if the id matched
   */
  public boolean isIdMatched() {
    return idMatched;
  }

  @Override
  public String toString() {
    return "JsonRpcDataException{code=" + getStatus() + ", message=" + getErrorMessage() + "}";
  }
}
