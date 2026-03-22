
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
   * Create exception from http code and error message.
   *
   * @param status  status code
   * @param message error message
   */
  public JsonRpcNetworkException(int status, String message) {
    super(String.format("Network error [%s] occurred: %s", status, message));
    this.status = status;
    this.errorMessage = message;
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
