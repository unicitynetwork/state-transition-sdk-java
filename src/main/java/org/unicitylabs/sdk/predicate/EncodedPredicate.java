package org.unicitylabs.sdk.predicate;

import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializationException;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.util.HexConverter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class EncodedPredicate implements Predicate {
    public static final long CBOR_TAG = 39032;

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

    public static EncodedPredicate fromCbor(byte[] bytes) {
        CborDeserializer.CborTag tag = CborDeserializer.decodeTag(bytes);
        if (tag.getTag() != EncodedPredicate.CBOR_TAG) {
            throw new CborSerializationException(String.format("Invalid CBOR tag: %s", tag.getTag()));
        }
        List<byte[]> data = CborDeserializer.decodeArray(tag.getData());
        PredicateEngine engine = PredicateEngine.fromId(
                CborDeserializer.decodeUnsignedInteger(data.get(0)).asInt());

        return new EncodedPredicate(
                engine,
                CborDeserializer.decodeByteString(data.get(1)),
                CborDeserializer.decodeByteString(data.get(2))
        );
    }

    public static EncodedPredicate fromPredicate(Predicate predicate) {
        return new EncodedPredicate(predicate.getEngine(), predicate.encodeCode(),
                predicate.encodeParameters());
    }

    @Override
    public byte[] encodeCode() {
        return Arrays.copyOf(this.code, this.code.length);
    }

    @Override
    public byte[] encodeParameters() {
        return Arrays.copyOf(this.parameters, this.parameters.length);
    }

    public byte[] toCbor() {
        return CborSerializer.encodeTag(
                EncodedPredicate.CBOR_TAG,
                CborSerializer.encodeArray(
                        CborSerializer.encodeUnsignedInteger(this.engine.getId()),
                        CborSerializer.encodeByteString(this.code),
                        CborSerializer.encodeByteString(this.parameters)
                )
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
        return "EncodedPredicate{" +
                "engine=" + this.engine +
                ", code=" + HexConverter.encode(this.code) +
                ", parameters=" + HexConverter.encode(this.parameters) +
                '}';
    }
}
