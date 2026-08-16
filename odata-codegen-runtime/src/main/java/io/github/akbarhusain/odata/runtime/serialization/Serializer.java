package io.github.akbarhusain.odata.runtime.serialization;

public interface Serializer {
    <T> byte[] serialize(T value, Class<T> type);

    /**
     * Serializes only the named fields (partial PATCH bodies). The default falls back to
     * full serialization for custom implementations that do not support filtering.
     */
    default <T> byte[] serialize(T value, Class<T> type, java.util.Set<String> includeFields) {
        return serialize(value, type);
    }
    <T> T deserialize(byte[] data, Class<T> type);
    <T> T deserialize(byte[] data, java.lang.reflect.Type type);

    static Serializer createDefault() {
        return new JacksonSerializer();
    }
}
