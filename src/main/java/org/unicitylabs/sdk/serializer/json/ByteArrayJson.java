package org.unicitylabs.sdk.serializer.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Byte array serializer and deserializer implementation.
 */
public class ByteArrayJson {

  private ByteArrayJson() {
  }

  /**
   * Byte array serializer.
   */
  public static class Serializer extends StdSerializer<byte[]> {

    /**
     * Create serializer.
     */
    public Serializer() {
      super(byte[].class);
    }

    /**
     * Serialize byte array.
     *
     * @param value       byte array
     * @param gen         json generator
     * @param serializers serializer provider
     * @throws IOException on serialization failure
     */
    @Override
    public void serialize(byte[] value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      gen.writeString(HexConverter.encode(value));
    }
  }

  /**
   * Byte array deserializer.
   */
  public static class Deserializer extends StdDeserializer<byte[]> {

    /**
     * Create deserializer.
     */
    public Deserializer() {
      super(byte[].class);
    }

    /**
     * Deserialize byte array.
     *
     * @param p   Parser used for reading JSON content
     * @param ctx Context that can be used to access information about this deserialization activity.
     * @return bytes
     * @throws IOException on deserialization failure
     */
    @Override
    public byte[] deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
      if (p.getCurrentToken() != JsonToken.VALUE_STRING) {
        throw MismatchedInputException.from(p, byte[].class,
            "Expected hex string value");
      }

      try {
        return HexConverter.decode(p.readValueAs(String.class));
      } catch (Exception e) {
        throw MismatchedInputException.from(p, byte[].class, "Expected hex string value");
      }
    }
  }
}

