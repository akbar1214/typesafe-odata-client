package io.github.akbarhusain.odata.runtime.serialization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JacksonSerializerTest {

    public record Sample(String name, int age) {}

    // Simulates the generated code pattern: entities have @JsonCreator + @JsonProperty
    // and a subtype with additional fields.
    public static class Animal {
        protected final String name;

        @JsonCreator
        public Animal(@JsonProperty("name") String name) {
            this.name = name;
        }

        public String getName() { return name; }
    }

    public static class Dog extends Animal {
        protected final String breed;

        @JsonCreator
        public Dog(@JsonProperty("name") String name, @JsonProperty("breed") String breed) {
            super(name);
            this.breed = breed;
        }

        public String getBreed() { return breed; }
    }

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

    @Test
    void polymorphicSerializationRespectsDeclaredType() {
        JacksonSerializer serializer = new JacksonSerializer();
        Dog dog = new Dog("Fido", "Labrador");

        // Serialize as the base type — should only include base fields
        byte[] bytes = serializer.serialize(dog, Animal.class);
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertTrue(json.contains("name"), "Base type field 'name' should be present: " + json);
        assertFalse(json.contains("breed"), "Subtype field 'breed' should NOT be present: " + json);
    }
}
