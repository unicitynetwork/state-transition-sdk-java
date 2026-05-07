package org.unicitylabs.sdk.jsonrpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcRequest;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;

import java.util.List;
import java.util.UUID;

public class JsonRpcRequestTest {

  @Test
  public void testJsonSerialization() throws JsonProcessingException {
    JsonRpcRequest request = new JsonRpcRequest(
            UUID.fromString("42b9d83f-f984-4c81-9a53-fa1bdbd30b99"),
            "testMethod",
            List.of("param1", "param2")
    );
    Assertions.assertEquals(
            "{\"id\":\"42b9d83f-f984-4c81-9a53-fa1bdbd30b99\",\"method\":\"testMethod\",\"params\":[\"param1\",\"param2\"],\"jsonrpc\":\"2.0\"}",
            UnicityObjectMapper.JSON.writeValueAsString(request));
  }

}
