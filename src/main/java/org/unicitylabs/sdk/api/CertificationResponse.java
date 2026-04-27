package org.unicitylabs.sdk.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.serializer.json.JsonSerializationException;

/**
 * Submit commitment response.
 */
public class CertificationResponse {

  private final CertificationStatus status;

  /**
   * Create submit commitment response.
   *
   * @param status status
   */
  @JsonCreator
  CertificationResponse(
          @JsonProperty("status") CertificationStatus status
  ) {
    this.status = status;
  }

  /**
   * Get status.
   *
   * @return status
   */
  public CertificationStatus getStatus() {
    return this.status;
  }

  /**
   * Create a new certification response.
   *
   * @param status Certification response status
   * @return certification response
   */
  public static CertificationResponse create(CertificationStatus status) {
    return new CertificationResponse(status);
  }

  /**
   * Create submit commitment response from JSON string.
   *
   * @param input JSON string
   * @return submit commitment response
   */
  public static CertificationResponse fromJson(String input) {
    try {
      return UnicityObjectMapper.JSON.readValue(input, CertificationResponse.class);
    } catch (JsonProcessingException e) {
      throw new JsonSerializationException(CertificationResponse.class, e);
    }
  }

  /**
   * Convert submit commitment response to JSON string.
   *
   * @return JSON string
   */
  public String toJson() {
    try {
      return UnicityObjectMapper.JSON.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new JsonSerializationException(CertificationResponse.class, e);
    }
  }

  @Override
  public String toString() {
    return String.format("SubmitCommitmentResponse{status=%s}", this.status);
  }
}
