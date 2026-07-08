package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.http.*;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that executeAndGetCollection correctly parses OData collection responses
 * using the optimized direct-deserialization path (no re-serialization via convertValue).
 */
class EntityOperationsCollectionParseTest {

    record Person(String UserName, String FirstName, String LastName) {}

    private HttpTransport stubTransport(String json) {
        return new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return CompletableFuture.completedFuture(new HttpResponse(200,
                        Map.of("Content-Type", List.of("application/json")),
                        json.getBytes(StandardCharsets.UTF_8)));
            }
            @Override
            public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void parseCollectionWithItems() {
        String json = "{\"value\":[{\"UserName\":\"scott\",\"FirstName\":\"Scott\",\"LastName\":\"Ketchum\"},"
                + "{\"UserName\":\"keith\",\"FirstName\":\"Keith\",\"LastName\":\"Harris\"}],"
                + "\"@odata.count\":2}";

        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(stubTransport(json))
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        CollectionPage<Person> page = EntityOperations.executeAndGetCollection(ctx, path, Person.class);

        assertEquals(2, page.currentPage().size());
        assertEquals("scott", page.currentPage().get(0).UserName());
        assertEquals("Scott", page.currentPage().get(0).FirstName());
        assertEquals("keith", page.currentPage().get(1).UserName());
        assertEquals("Keith", page.currentPage().get(1).FirstName());
        assertEquals(2L, page.count().orElse(0L));
    }

    @Test
    void parseCollectionEmpty() {
        String json = "{\"value\":[]}";

        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(stubTransport(json))
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        CollectionPage<Person> page = EntityOperations.executeAndGetCollection(ctx, path, Person.class);

        assertTrue(page.currentPage().isEmpty());
        assertFalse(page.hasNextPage());
    }

    @Test
    void parseCollectionWithNextLink() {
        String json = "{\"value\":[{\"UserName\":\"scott\",\"FirstName\":\"Scott\",\"LastName\":\"Ketchum\"}],"
                + "\"@odata.nextLink\":\"https://example.com/People?$skip=10\"}";

        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(stubTransport(json))
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        CollectionPage<Person> page = EntityOperations.executeAndGetCollection(ctx, path, Person.class);

        assertEquals(1, page.currentPage().size());
        assertTrue(page.hasNextPage());
        assertEquals("https://example.com/People?$skip=10", page.getNextLink());
    }

    @Test
    void parseCollectionWithNestedObjects() {
        record Address(String City, String Country) {}
        record PersonWithAddress(String UserName, Address Address) {}

        String json = "{\"value\":[{\"UserName\":\"scott\",\"Address\":{\"City\":\"Redmond\",\"Country\":\"USA\"}}]}";

        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(stubTransport(json))
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        CollectionPage<PersonWithAddress> page = EntityOperations.executeAndGetCollection(ctx, path, PersonWithAddress.class);

        assertEquals(1, page.currentPage().size());
        assertEquals("scott", page.currentPage().get(0).UserName());
        assertEquals("Redmond", page.currentPage().get(0).Address().City());
        assertEquals("USA", page.currentPage().get(0).Address().Country());
    }
}
