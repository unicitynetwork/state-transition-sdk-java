
package org.unicitylabs.sdk.api.jsonrpc;

/**
 * JSON RPC network exception.
 */
public class JsonRpcNetworkException extends Exception {

  /**
   * Status code.
   */
  private final int status;
  /**
   * Error message.
   */
  private final String errorMessage;
  /**
   * The service's own error, when the body is a matching JSON-RPC error envelope.
   */
  private final transient JsonRpcDataException rpcError;

  /**
   * Create exception from http code and error message.
   *
   * @param status  status code
   * @param message error message
   */
  public JsonRpcNetworkException(int status, String message) {
    this(status, message, null);
  }

  /**
   * Create exception of a non-success HTTP status.
   *
   * @param status   status code
   * @param message  body of the response
   * @param rpcError the service's own error when the body is a complete JSON-RPC error envelope for
   *                 this very request, null otherwise
   */
  public JsonRpcNetworkException(int status, String message, JsonRpcDataException rpcError) {
    super(String.format("Network error [%s] occurred: %s", status, message));
    this.status = status;
    this.errorMessage = message;
    this.rpcError = rpcError;
  }

  /**
   * The service's own error behind a non-success HTTP status. Some deployments answer their
   * JSON-RPC errors with 404, 409 or 429. A body that is not a matching envelope (a proxy page,
   * another id) says nothing about the request.
   *
   * @return the error, null if there is none
   */
  public JsonRpcDataException getRpcError() {
    return this.rpcError;
  }

  /**
   * Get status code.
   *
   * @return status code
   */
  public int getStatus() {
    return this.status;
  }

  /**
   * Get error message.
   *
   * @return error message
   */
  public String getErrorMessage() {
    return this.errorMessage;
  }
}
