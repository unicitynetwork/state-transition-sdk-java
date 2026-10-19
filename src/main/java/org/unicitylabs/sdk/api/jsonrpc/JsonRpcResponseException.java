package org.unicitylabs.sdk.api.jsonrpc;

/**
 * The service answered with something that is not a valid JSON-RPC response for the request: bad
 * JSON, a wrong version, a mismatching id, both or neither of result and error, or a body over the
 * size limit.
 */
public class JsonRpcResponseException extends RuntimeException {

  /**
   * Creates the exception.
   *
   * @param message description
   */
  public JsonRpcResponseException(String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message description
   * @param cause cause
   */
  public JsonRpcResponseException(String message, Throwable cause) {
    super(message, cause);
  }
}
