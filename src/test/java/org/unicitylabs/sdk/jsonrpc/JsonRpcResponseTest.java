package org.unicitylabs.sdk.jsonrpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.BlockHeightResponse;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcResponse;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;

public class JsonRpcResponseTest {

  @Test
  public void testJsonSerialization() throws JsonProcessingException {
    JsonRpcResponse<BlockHeightResponse> data = UnicityObjectMapper.JSON.readValue(
            "{\"jsonrpc\":\"2.0\",\"result\":{\"blockNumber\":\"846973\"},\"id\":\"60ce8f4d-4c78-4690-a330-a92d3cf497a9\"}",
            UnicityObjectMapper.JSON.getTypeFactory()
                    .constructParametricType(JsonRpcResponse.class, BlockHeightResponse.class));

    Assertions.assertEquals("60ce8f4d-4c78-4690-a330-a92d3cf497a9", data.getId().toString());
    Assertions.assertEquals("2.0", data.getVersion());
    Assertions.assertInstanceOf(BlockHeightResponse.class, data.getResult());
    Assertions.assertEquals(846973L, data.getResult().getBlockNumber());
    Assertions.assertNull(data.getError());
  }

}
