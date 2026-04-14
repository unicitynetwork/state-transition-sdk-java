package org.unicitylabs.sdk.predicate;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

/**
 * Generic predicate representation that stores engine, code, and parameters as encoded bytes.
 */
public class EncodedPredicate implements Predicate {

  private final PredicateEngine engine;
  private final byte[] code;
  private final byte[] parameters;

  private EncodedPredicate(PredicateEngine engine, byte[] code, byte[] parameters) {
    this.engine = engine;
    this.code = code;
    this.parameters = parameters;
  }

  @Override
  public PredicateEngine getEngine() {
    return this.engine;
  }

  /**
   * Deserializes an encoded predicate from CBOR.
   *
   * @param bytes CBOR-encoded predicate bytes
   * @return decoded encoded predicate
   */
  public static EncodedPredicate fromCbor(byte[] bytes) {
    List<byte[]> data = CborDeserializer.decodeArray(bytes);
    PredicateEngine engine = PredicateEngine.fromId(
        CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt());

    return new EncodedPredicate(
        engine,
        CborDeserializer.decodeByteString(data.get(1)),
        CborDeserializer.decodeByteString(data.get(2))
    );
  }

  /**
   * Creates an encoded predicate snapshot from any predicate implementation.
   *
   * @param predicate source predicate
   * @return encoded predicate containing engine, code, and parameters
   */
  public static EncodedPredicate fromPredicate(Predicate predicate) {
    return new EncodedPredicate(
        predicate.getEngine(),
        predicate.encodeCode(),
        predicate.encodeParameters()
    );
  }

  @Override
  public byte[] encodeCode() {
    return Arrays.copyOf(this.code, this.code.length);
  }

  @Override
  public byte[] encodeParameters() {
    return Arrays.copyOf(this.parameters, this.parameters.length);
  }

  /**
   * Serializes this predicate into CBOR.
   *
   * @return CBOR-encoded predicate bytes
   */
  public byte[] toCbor() {
    return CborSerializer.encodeArray(
        CborSerializer.encodeUnsignedInteger(this.engine.getId()),
        CborSerializer.encodeByteString(this.code),
        CborSerializer.encodeByteString(this.parameters)
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof EncodedPredicate)) {
      return false;
    }
    EncodedPredicate that = (EncodedPredicate) o;
    return this.engine == that.engine && Arrays.equals(this.code, that.code) && Arrays.equals(
        this.parameters, that.parameters);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.engine, Arrays.hashCode(this.code), Arrays.hashCode(this.parameters));
  }

  @Override
  public String toString() {
    return String.format(
        "EncodedPredicate{engine=%s, code=%s, parameters=%s}",
        this.engine,
        HexConverter.encode(this.code),
        HexConverter.encode(this.parameters)
    );
  }
}
