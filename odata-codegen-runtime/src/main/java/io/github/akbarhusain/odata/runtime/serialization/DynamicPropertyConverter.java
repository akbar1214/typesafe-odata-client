package io.github.akbarhusain.odata.runtime.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Converts a dynamically-captured OData property (stored as a Jackson "natural" value such as
 * {@code String}, {@code Integer}/{@code Long}/{@code Double}, {@code Boolean}, {@code LinkedHashMap},
 * or {@code ArrayList}) into a caller-supplied target type via {@link ObjectMapper#convertValue}.
 *
 * <p>The stored value is already a Jackson-bound object (it came from {@code @JsonAnySetter}), so
 * {@code convertValue} is the correct tool — there are no JSON bytes to re-parse, and the pluggable
 * {@link Serializer} cannot operate on an in-memory {@code Object}.</p>
 */
public final class DynamicPropertyConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule());

    private DynamicPropertyConverter() {}

    public static <T> T convert(Object value, Class<T> type) {
        try {
            return MAPPER.convertValue(value, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot convert dynamic property to " + type.getName() + ": " + e.getMessage(), e);
        }
    }
}
