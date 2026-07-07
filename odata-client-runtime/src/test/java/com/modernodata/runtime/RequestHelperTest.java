package com.modernodata.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.modernodata.runtime.entity.Context;
import com.modernodata.runtime.entity.ContextPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestHelperTest {

    record TestEntity(String name, int age) {}

    private ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        try {
            Class<?> pnClass = Class.forName("com.fasterxml.jackson.module.paramnames.ParameterNamesModule");
            mapper.registerModule((com.fasterxml.jackson.databind.Module) pnClass.getDeclaredConstructor().newInstance());
        } catch (Exception ignored) {}
        return mapper;
    }

    @Test
    void parseODataCollectionResponse() throws Exception {
        String json = """
                {
                    "value": [
                        {"name": "Alice", "age": 30},
                        {"name": "Bob", "age": 25}
                    ],
                    "@odata.nextLink": "https://example.com/people?$skip=2"
                }
                """;

        ObjectMapper mapper = createMapper();
        JavaType listType = mapper.getTypeFactory()
                .constructCollectionType(java.util.List.class, TestEntity.class);

        JsonNode root = mapper.readTree(json);
        List<TestEntity> items = mapper.convertValue(root.get("value"), listType);
        String nextLink = root.has("@odata.nextLink") ? root.get("@odata.nextLink").asText() : null;

        assertEquals(2, items.size());
        assertEquals("Alice", items.get(0).name());
        assertEquals(30, items.get(0).age());
        assertEquals("https://example.com/people?$skip=2", nextLink);
    }

    @Test
    void parseODataCollectionResponseNoNextLink() throws Exception {
        String json = """
                {
                    "value": [
                        {"name": "Charlie", "age": 40}
                    ]
                }
                """;

        ObjectMapper mapper = createMapper();
        JavaType listType = mapper.getTypeFactory()
                .constructCollectionType(java.util.List.class, TestEntity.class);

        JsonNode root = mapper.readTree(json);
        List<TestEntity> items = mapper.convertValue(root.get("value"), listType);
        String nextLink = root.has("@odata.nextLink") ? root.get("@odata.nextLink").asText() : null;

        assertEquals(1, items.size());
        assertNull(nextLink);
    }

    @Test
    void parseODataCollectionResponseEmpty() throws Exception {
        String json = """
                {
                    "value": []
                }
                """;

        ObjectMapper mapper = createMapper();
        JavaType listType = mapper.getTypeFactory()
                .constructCollectionType(java.util.List.class, TestEntity.class);

        JsonNode root = mapper.readTree(json);
        List<TestEntity> items = mapper.convertValue(root.get("value"), listType);

        assertTrue(items.isEmpty());
    }

    @Test
    void contextBaseUrlCombinesCorrectly() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        assertEquals("https://services.odata.org/V4/TripPinService/People", path.toUrl());
    }

    @Test
    void contextPathWithQueryParameters() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        ContextPath path = ctx.basePath()
                .addSegment("People")
                .addQuery("$filter", "FirstName eq 'Scott'")
                .addQuery("$top", "5");

        String url = path.toUrl();
        assertTrue(url.contains("$filter=FirstName"), "URL should contain filter: " + url);
        assertTrue(url.contains("%20eq%20"), "URL should have encoded spaces: " + url);
        assertTrue(url.contains("$top=5"), "URL should contain top: " + url);
        assertTrue(url.startsWith("https://services.odata.org/V4/TripPinService/People?"),
                "URL should start with base: " + url);
    }

    @Test
    void contextPathWithKey() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        ContextPath path = ctx.basePath()
                .addSegment("People")
                .addKey("UserName", "scottketchum");

        assertEquals("https://services.odata.org/V4/TripPinService/People('scottketchum')", path.toUrl());
    }
}
