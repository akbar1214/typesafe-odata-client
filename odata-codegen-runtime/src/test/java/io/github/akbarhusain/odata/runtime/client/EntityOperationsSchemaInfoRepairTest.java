package io.github.akbarhusain.odata.runtime.client;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.entity.ODataEntityType;
import io.github.akbarhusain.odata.runtime.entity.SchemaInfo;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EntityOperationsSchemaInfoRepairTest {

    @Test
    void schemaInfoRepairDoesNotOverwriteLifecycleStateOrCaptureLifecycleFieldsAsDynamicProperties() {
        String json = "{"
                + "\"@odata.type\":\"#Test.LifecycleEntity\","
                + "\"Name\":\"server\","
                + "\"Expanded\":{\"Name\":\"nested\"},"
                + "\"changedFields\":[\"evil\"],"
                + "\"unmappedFields\":{\"evil\":true},"
                + "\"contextPath\":null,"
                + "\"etag\":\"evil-etag\","
                + "\"key\":\"evil-key\""
                + "}";
        Context context = Context.builder()
                .baseUrl("https://example.test/service")
                .transport(new SingleResponseTransport(json))
                .build();

        LifecycleEntity result = EntityOperations.executeAndGetEntity(context,
                context.basePath().addSegment("Things"), LifecycleEntity.class,
                new TestSchemaInfo());

        assertEquals("https://trusted", result.getContextPath().toUrl());
        assertEquals(Set.of("trusted-change"), result.getChangedFields());
        assertEquals("trusted-etag", result.getETag().orElseThrow());
        assertEquals("trusted-key", result.getKey());
        assertEquals("yes", result.getUnmappedFields().get("kept"));
        assertEquals("nested", result.getExpanded().getName());
        for (String lifecycle : List.of("changedFields", "unmappedFields", "contextPath", "etag", "key")) {
            assertFalse(result.getUnmappedFields().containsKey(lifecycle), lifecycle);
        }
    }

    private static final class TestSchemaInfo implements SchemaInfo {
        @Override
        public Class<?> getClassFromTypeWithNamespace(String name) {
            return "Test.LifecycleEntity".equals(name) ? LifecycleEntity.class : null;
        }
    }

    private static final class SingleResponseTransport implements HttpTransport {
        private final byte[] body;

        private SingleResponseTransport(String body) {
            this.body = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            return CompletableFuture.completedFuture(new HttpResponse(200, Map.of(), body));
        }

        @Override
        public CompletableFuture<InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    public static final class LifecycleEntity implements ODataEntityType {
        private String name;
        private Expanded expanded;
        private String etag = "trusted-etag";
        private ContextPath contextPath = new ContextPath("https://trusted");
        private Map<String, Object> unmappedFields = new HashMap<>();
        private Set<String> changedFields = new HashSet<>();
        private Object key = "trusted-key";

        public LifecycleEntity() {
            unmappedFields.put("kept", "yes");
            changedFields.add("trusted-change");
        }

        @JsonProperty("Name")
        public void setName(String name) {
            this.name = name;
        }

        @JsonProperty("Expanded")
        public void setExpanded(Expanded expanded) {
            this.expanded = expanded;
        }

        @JsonAnySetter
        public void setDynamicProperty(String name, Object value) {
            unmappedFields.put(name, value);
        }

        public String getName() {
            return name;
        }

        public Expanded getExpanded() {
            return expanded;
        }

        @Override
        public String odataTypeName() {
            return "Test.LifecycleEntity";
        }

        @Override
        @JsonIgnore
        public Map<String, Object> getUnmappedFields() {
            return Collections.unmodifiableMap(unmappedFields);
        }

        @Override
        @JsonIgnore
        public ContextPath getContextPath() {
            return contextPath;
        }

        @Override
        @JsonIgnore
        public Set<String> getChangedFields() {
            return Set.copyOf(changedFields);
        }

        @Override
        @JsonIgnore
        public Object getKey() {
            return key;
        }

        @Override
        @JsonIgnore
        public Optional<String> getETag() {
            return Optional.ofNullable(etag);
        }
    }

    public static final class Expanded {
        private String name;

        @JsonProperty("Name")
        public void setName(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
