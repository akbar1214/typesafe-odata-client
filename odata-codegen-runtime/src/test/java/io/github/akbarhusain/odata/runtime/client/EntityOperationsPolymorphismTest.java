package io.github.akbarhusain.odata.runtime.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.entity.SchemaInfo;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M23: base-typed reads must honor {@code @odata.type} via the SchemaInfo registry so
 * subtype properties survive deserialization instead of being silently dropped.
 */
class EntityOperationsPolymorphismTest {

    public static class Animal {
        @JsonProperty("name") protected String name;
        public String getName() { return name; }
    }

    public static class Cat extends Animal {
        @JsonProperty("livesIndoors") protected Boolean livesIndoors;
        public Boolean getLivesIndoors() { return livesIndoors; }
    }

    static class TestSchemaInfo implements SchemaInfo {
        @Override
        public Class<?> getClassFromTypeWithNamespace(String name) {
            return "Test.Cat".equals(name) ? Cat.class : null;
        }
    }

    private Context context(String json) {
        HttpTransport transport = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return CompletableFuture.completedFuture(new HttpResponse(200,
                        Map.of("Content-Type", List.of("application/json")),
                        json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }

            @Override
            public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        return Context.builder().baseUrl("https://example.com").transport(transport).build();
    }

    @Test
    void m23EntityGetResolvesODataTypeToSubtype() {
        Context ctx = context("{\"@odata.type\":\"#Test.Cat\",\"name\":\"Tom\",\"livesIndoors\":true}");
        ContextPath path = ctx.basePath().addSegment("Animals").addKey("Id", 1);

        Animal result = EntityOperations.executeAndGetEntity(ctx, path, Animal.class, new TestSchemaInfo());

        assertInstanceOf(Cat.class, result, "@odata.type must select the subtype");
        assertEquals("Tom", result.getName(), "base property must survive");
        assertEquals(Boolean.TRUE, ((Cat) result).getLivesIndoors(),
                "subtype property must not be silently dropped");
    }

    @Test
    void m23EntityGetFallsBackToDeclaredTypeWithoutODataType() {
        Context ctx = context("{\"name\":\"Generic\"}");
        ContextPath path = ctx.basePath().addSegment("Animals").addKey("Id", 1);

        Animal result = EntityOperations.executeAndGetEntity(ctx, path, Animal.class, new TestSchemaInfo());
        assertEquals("Generic", result.getName());
    }

    @Test
    void m23CollectionResolvesPerElementODataType() {
        Context ctx = context("{\"value\":["
                + "{\"@odata.type\":\"#Test.Cat\",\"name\":\"Tom\",\"livesIndoors\":false},"
                + "{\"name\":\"Plain\"}]}");
        ContextPath path = ctx.basePath().addSegment("Animals");

        CollectionPage<Animal> page = EntityOperations.executeAndGetCollection(
                ctx, path, Animal.class, new TestSchemaInfo());

        assertEquals(2, page.currentPage().size());
        assertInstanceOf(Cat.class, page.currentPage().get(0),
                "element @odata.type must select the subtype");
        assertEquals(Boolean.FALSE, ((Cat) page.currentPage().get(0)).getLivesIndoors());
        assertEquals("Plain", page.currentPage().get(1).getName());
        assertEquals(Animal.class, page.currentPage().get(1).getClass(),
                "elements without @odata.type use the declared type");
    }
}
