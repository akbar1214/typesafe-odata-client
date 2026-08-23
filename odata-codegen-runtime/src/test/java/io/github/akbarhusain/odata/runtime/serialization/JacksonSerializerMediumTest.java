package io.github.akbarhusain.odata.runtime.serialization;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M11: NON_EMPTY hides intentional empty collection clear
 */
class JacksonSerializerMediumTest {

    public static class Holder {
        @JsonProperty("tags")
        public List<String> tags;
        @JsonProperty("name")
        public String name;
        public Holder() {}
        public Holder(List<String> tags, String name) { this.tags = tags; this.name = name; }
    }

    @Test
    void m11_emptyCollectionShouldBeSerializableWhenExplicitlyIncluded() throws Exception {
        JacksonSerializer ser = new JacksonSerializer();
        Holder h = new Holder(List.of(), "test");
        // Intentional pin: full serialization keeps NON_EMPTY, so an empty collection is
        // omitted by default (decision 35). Only the includeFields PATCH path must emit it.
        byte[] defaultBytes = ser.serialize(h, Holder.class);
        String defaultJson = new String(defaultBytes);
        assertFalse(defaultJson.contains("\"tags\""),
                "default serialization intentionally omits empty collections (NON_EMPTY): " + defaultJson);

        // Fixed behavior (M11): when includeFields contains "tags", empty list serializes as []
        byte[] withEmpty = ser.serialize(h, Holder.class, java.util.Set.of("tags", "name"));
        String withJson = new String(withEmpty);
        assertTrue(withJson.contains("\"tags\"") && withJson.contains("[]"),
                "M11: explicit includeFields should serialize empty collection as [], got: " + withJson);
    }

    @Test
    void m11_nonEmptyStillSerialized() {
        JacksonSerializer ser = new JacksonSerializer();
        Holder h = new Holder(List.of("a", "b"), "test");
        byte[] bytes = ser.serialize(h, Holder.class);
        String json = new String(bytes);
        assertTrue(json.contains("\"tags\"") && json.contains("\"a\""), "non-empty should be serialized: " + json);
    }
    @Test
    void m11_defaultEmptyNotSerializedControl() {
        JacksonSerializer ser = new JacksonSerializer();
        Holder h = new Holder(List.of(), "x");
        String json = new String(ser.serialize(h, Holder.class));
        // Intentional pin of the NON_EMPTY default (decision 35): empty collections are
        // omitted from full serialization; only the includeFields PATCH path overrides
        assertFalse(json.contains("tags"),
                "default serialization intentionally omits empty collections (NON_EMPTY): " + json);
    }
}
