package io.github.akbarhusain.odata.runtime.serialization;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JacksonSerializerTest {

    public record Sample(String name, int age) {}

    @Test
    void wireSerializerIsCompact() {
        JacksonSerializer serializer = new JacksonSerializer();
        byte[] bytes = serializer.serialize(new Sample("alice", 30), Sample.class);
        String json = new String(bytes, StandardCharsets.UTF_8);

        // Wire bodies must not be pretty-printed (no newlines / indentation).
        assertFalse(json.contains("\n"),
                "Wire serializer should be compact, but produced:\n" + json);
    }

    @Test
    void debugToJsonStaysPrettyPrinted() {
        JacksonSerializer serializer = new JacksonSerializer();
        String json = serializer.toJson(new Sample("alice", 30));

        // Debug helper keeps human-readable formatting.
        assertTrue(json.contains("\n"),
                "toJson() should remain pretty-printed for debugging, but produced:\n" + json);
    }
}
