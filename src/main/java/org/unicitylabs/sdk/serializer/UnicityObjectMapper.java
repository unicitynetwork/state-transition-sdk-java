package org.unicitylabs.sdk.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.unicitylabs.sdk.serializer.json.ByteArrayJson;


/**
 * Unicity object mapper with byte array and JDK8 module attached.
 */
public class UnicityObjectMapper {

  /**
   * JSON object mapper with correct modules attached.
   */
  public static final ObjectMapper JSON = createJsonObjectMapper();

  private UnicityObjectMapper() {
  }

  private static ObjectMapper createJsonObjectMapper() {
    SimpleModule module = new SimpleModule();
    module.addSerializer(byte[].class, new ByteArrayJson.Serializer());
    module.addDeserializer(byte[].class, new ByteArrayJson.Deserializer());

    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new Jdk8Module());
    objectMapper.registerModule(module);
    return objectMapper;
  }
}