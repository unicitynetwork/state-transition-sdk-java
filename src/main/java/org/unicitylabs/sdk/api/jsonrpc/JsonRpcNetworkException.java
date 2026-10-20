
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
   * The {@code Retry-After} header of the response in seconds, null if absent or not a number of
   * seconds.
   */
  private final Long retryAfterSeconds;

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
    this(status, message, rpcError, null);
  }

  /**
   * Create exception of a non-success HTTP status with the server's retry hint.
   *
   * @param status   status code
   * @param message  body of the response
   * @param rpcError the service's own error when the body is a complete JSON-RPC error envelope for
   *                 this very request, null otherwise
   * @param retryAfterSeconds the {@code Retry-After} header in seconds, null if there is none
   */
  public JsonRpcNetworkException(int status, String message, JsonRpcDataException rpcError,
      Long retryAfterSeconds) {
    super(String.format("Network error [%s] occurred: %s", status, message));
    this.status = status;
    this.errorMessage = message;
    this.rpcError = rpcError;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /**
   * The {@code Retry-After} header of the response, in seconds. Unauthenticated, like the rest of
   * the response; a client only uses it to pace a retry.
   *
   * @return seconds, null if the header was absent or not a non-negative number of seconds
   */
  public Long getRetryAfterSeconds() {
    return this.retryAfterSeconds;
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
