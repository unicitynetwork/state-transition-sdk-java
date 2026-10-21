package org.unicitylabs.sdk.serializer.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.math.BigInteger;
import org.unicitylabs.sdk.util.UnsignedLongs;

/**
 * JSON mapping of protocol numbers that use the full unsigned 64-bit range (0..2^64-1) and are
 * carried as raw {@code long} bits in Java. Reading accepts a JSON integer or a decimal string and
 * never goes through {@code double}; writing produces the unsigned decimal as a string, as the JSON
 * form of the trust base always did.
 */
public final class UnsignedLongJson {

  private UnsignedLongJson() {
  }

  /** Writes unsigned bits as an unsigned decimal string. */
  public static class Serializer extends StdSerializer<Long> {

    /** Creates the serializer. */
    public Serializer() {
      super(Long.class);
    }

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      gen.writeString(UnsignedLongs.toDecimal(value));
    }
  }

  /** Reads an unsigned 64-bit number from a JSON integer or decimal string. */
  public static class Deserializer extends StdDeserializer<Long> {

    /** Creates the deserializer. */
    public Deserializer() {
      super(Long.class);
    }

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
      JsonToken token = parser.currentToken();
      BigInteger value;
      if (token == JsonToken.VALUE_NUMBER_INT) {
        value = parser.getBigIntegerValue();
      } else if (token == JsonToken.VALUE_STRING) {
        String text = parser.getText();
        if (text.isEmpty() || text.length() > 20 || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
          throw JsonMappingException.from(parser,
              "Expected an unsigned decimal integer, got: " + abbreviate(text));
        }
        value = new BigInteger(text);
      } else {
        throw JsonMappingException.from(parser, "Expected an unsigned integer, got " + token + ".");
      }
      try {
        return UnsignedLongs.fromBigInteger(value);
      } catch (IllegalArgumentException e) {
        throw JsonMappingException.from(parser,
            "Value is out of the unsigned 64-bit range (0..2^64-1).", e);
      }
    }

    private static String abbreviate(String text) {
      return text.length() > 24 ? text.substring(0, 24) + "..." : text;
    }
  }
}
