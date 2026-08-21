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
        // Current NON_EMPTY hides empty list: {"name":"test"} without tags
        // After fix with includeEmpty or patched serialization, empty should be present when fields explicitly included
        // For now, test that default serialize hides it (bug), and that includeFields variant includes it
        byte[] defaultBytes = ser.serialize(h, Holder.class);
        String defaultJson = new String(defaultBytes);
        // Bug: default hides tags
        assertFalse(defaultJson.contains("\"tags\""), "default should hide empty (bug) - this asserts bug present, will be fixed to allow explicit include");

        // Fixed behavior: when includeFields contains "tags", empty list should be serialized as []
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
        // This documents current buggy behavior; after fix, default may still hide empty, but explicit include should show
        // We assert bug is present: empty is NOT in json
        assertFalse(json.contains("tags"), "M11 control: empty hidden before fix: " + json);
    }
}
