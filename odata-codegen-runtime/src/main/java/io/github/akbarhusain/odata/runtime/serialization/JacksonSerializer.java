package io.github.akbarhusain.odata.runtime.serialization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;

public class JacksonSerializer implements Serializer {

    private static final ObjectMapper MAPPER = createMapper();
    private static final ObjectMapper MAPPER_INCLUDE_NULLS = createMapperIncludeNulls();
    private static final ObjectMapper MAPPER_PRETTY = createMapperPretty();

    private static ObjectMapper createMapper() {
        return configureCollectionInclusion(baseMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_ABSENT));
    }

    private static ObjectMapper createMapperIncludeNulls() {
        return configureCollectionInclusion(baseMapper()
                .setSerializationInclusion(JsonInclude.Include.ALWAYS));
    }

    private static ObjectMapper createMapperPretty() {
        return configureCollectionInclusion(baseMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_ABSENT)
                .enable(SerializationFeature.INDENT_OUTPUT));
    }

    private static ObjectMapper configureCollectionInclusion(ObjectMapper mapper) {
        // Empty collections (e.g. navigation properties set to List.of()) should not be
        // serialized. Real services like TripPin reject POST bodies containing empty
        // navigation arrays with a 500 "Sequence contains no matching element" error.
        JsonInclude.Value nonEmpty = JsonInclude.Value.construct(JsonInclude.Include.NON_EMPTY, JsonInclude.Include.USE_DEFAULTS);
        mapper.configOverride(java.util.Collection.class).setInclude(nonEmpty);
        mapper.configOverride(java.util.List.class).setInclude(nonEmpty);
        mapper.configOverride(java.util.Set.class).setInclude(nonEmpty);
        return mapper;
    }

    private static ObjectMapper baseMapper() {
        return new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public <T> byte[] serialize(T value, Class<T> type) {
        try {
            return MAPPER.writerFor(type).writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                    "Serialization failed: " + e.getMessage(), e);
        }
    }

    public <T> byte[] serializeIncludeNulls(T value, Class<T> type) {
        try {
            return MAPPER_INCLUDE_NULLS.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                    "Serialization failed: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> type) {
        try {
            return MAPPER.readValue(data, type);
        } catch (IOException e) {
            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                    "Deserialization failed: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T deserialize(byte[] data, java.lang.reflect.Type type) {
        try {
            JavaType javaType = MAPPER.getTypeFactory().constructType(type);
            return MAPPER.readValue(data, javaType);
        } catch (IOException e) {
            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                    "Deserialization failed: " + e.getMessage(), e);
        }
    }

    public String serializeToString(Object value) {
        try {
            return MAPPER_PRETTY.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                    "Serialization failed: " + e.getMessage(), e);
        }
    }

    public String toJson(Object value) {
        try {
            return MAPPER_PRETTY.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                    "Serialization failed: " + e.getMessage(), e);
        }
    }
}
