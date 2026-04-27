package org.unicitylabs.sdk.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.serializer.json.JsonSerializationException;
import org.unicitylabs.sdk.serializer.json.LongAsStringSerializer;

import java.util.Objects;

/**
 * Block height response.
 */
public class BlockHeightResponse {

  private final long blockNumber;

  @JsonCreator
  private BlockHeightResponse(
          @JsonProperty("blockNumber") long blockNumber
  ) {
    this.blockNumber = blockNumber;
  }

  /**
   * Get block height.
   *
   * @return block height
   */
  @JsonSerialize(using = LongAsStringSerializer.class)
  public long getBlockNumber() {
    return this.blockNumber;
  }

  /**
   * Create response from JSON string.
   *
   * @param input JSON string
   * @return block height response
   */
  public static BlockHeightResponse fromJson(String input) {
    try {
      return UnicityObjectMapper.JSON.readValue(input, BlockHeightResponse.class);
    } catch (JsonProcessingException e) {
      throw new JsonSerializationException(BlockHeightResponse.class, e);
    }
  }

  /**
   * Convert response to JSON string.
   *
   * @return JSON string
   */
  public String toJson() {
    try {
      return UnicityObjectMapper.JSON.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new JsonSerializationException(BlockHeightResponse.class, e);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BlockHeightResponse)) {
      return false;
    }
    BlockHeightResponse that = (BlockHeightResponse) o;
    return this.blockNumber == that.blockNumber;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.blockNumber);
  }
}
