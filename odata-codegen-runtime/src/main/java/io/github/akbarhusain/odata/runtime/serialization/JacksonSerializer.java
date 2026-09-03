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
    private static final ObjectMapper MAPPER_FOR_PATCH = createMapperForPatch();

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

    private static ObjectMapper createMapperForPatch() {
        // For PATCH with includeFields, empty collections must be serialized when explicitly requested
        // (e.g., clearing tags with []), so don't hide NON_EMPTY
        return baseMapper().setSerializationInclusion(JsonInclude.Include.NON_ABSENT);
    }

    private static ObjectMapper baseMapper() {
        return new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // Shared mapper for DynamicPropertyConverter to avoid duplicate ObjectMapper config (L9)
    static ObjectMapper sharedMapper() {
        return MAPPER;
    }

    @Override
    public <T> byte[] serialize(T value, Class<T> type) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null (nothing to serialize)");
        }
        try {
            // Use the runtime class so subtype-only fields are preserved when a
            // subtype is posted/patched through a base-typed collection request
            // (e.g. posting a FeaturedProduct to the Products entity set).
            Class<?> effectiveType = value == null ? type : value.getClass();
            return MAPPER.writerFor(effectiveType).writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                    "Serialization failed: " + e.getMessage(), e);
        }
    }

    public <T> byte[] serializeIncludeNulls(T value, Class<T> type) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null (nothing to serialize)");
        }
        try {
            Class<?> effectiveType = value == null ? type : value.getClass();
            return MAPPER_INCLUDE_NULLS.writerFor(effectiveType).writeValueAsBytes(value);
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

    @Override
    public <T> byte[] serialize(T value, Class<T> type, java.util.Set<String> includeFields) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null (nothing to serialize)");
        }
        if (includeFields == null || includeFields.isEmpty()) {
            return serialize(value, type);
        }
        // Serialize through a tree, then keep only the changed fields — for PATCH with
        // explicit includeFields, empty collections must be included when requested (e.g., clearing)
        // so use mapper without NON_EMPTY override; full serialization still hides empty collections
        try {
            com.fasterxml.jackson.databind.JsonNode tree = MAPPER_FOR_PATCH.valueToTree(value);
            if (tree.isObject()) {
                // collect first (cannot mutate while iterating fieldNames)
                java.util.List<String> names = new java.util.ArrayList<>();
                tree.fieldNames().forEachRemaining(names::add);
                for (String name : names) {
                    if (!includeFields.contains(name)) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove(name);
                    }
                }
            }
            return MAPPER_FOR_PATCH.writeValueAsBytes(tree);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                    "Failed to serialize changed fields: " + e.getMessage(), e);
        }
    }
}
