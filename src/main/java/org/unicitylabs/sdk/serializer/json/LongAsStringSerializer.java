package org.unicitylabs.sdk.serializer.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Serializes a long value as a JSON string.
 */
public class LongAsStringSerializer extends JsonSerializer<Long> {

  /**
   * Create long serializer.
   */
  public LongAsStringSerializer() {
    super();
  }

  @Override
  public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers)
          throws IOException {
    gen.writeString(value.toString());
  }
}

