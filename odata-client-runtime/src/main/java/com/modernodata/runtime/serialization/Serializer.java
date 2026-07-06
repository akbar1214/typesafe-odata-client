package com.modernodata.runtime.serialization;

public interface Serializer {
    <T> byte[] serialize(T value, Class<T> type);
    <T> T deserialize(byte[] data, Class<T> type);
    <T> T deserialize(byte[] data, java.lang.reflect.Type type);

    static Serializer createDefault() {
        return new JacksonSerializer();
    }
}
