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
        record CityInfo(String name, String country) {}
        record PersonWithCity(String UserName, CityInfo homeCity) {}

        String json = "{\"value\":[{\"UserName\":\"scott\",\"homeCity\":{\"name\":\"Redmond\",\"country\":\"USA\"}}]}";

        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(stubTransport(json))
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        CollectionPage<PersonWithCity> page = EntityOperations.executeAndGetCollection(ctx, path, PersonWithCity.class);

        assertEquals(1, page.currentPage().size());
        assertEquals("scott", page.currentPage().get(0).UserName());
        assertEquals("Redmond", page.currentPage().get(0).homeCity().name());
        assertEquals("USA", page.currentPage().get(0).homeCity().country());
    }

    // Transport that captures the last request for URL assertions
    static class CapturingTransport implements HttpTransport {
        HttpRequest lastRequest;

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.lastRequest = request;
            return CompletableFuture.completedFuture(new HttpResponse(200,
                    Map.of("Content-Type", List.of("application/json")),
                    ("{\"value\":[]}").getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void searchQueryParamInUrl() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(transport)
                .build();

        ContextPath path = ctx.basePath().addSegment("Products").addQuery("$search", "bike");
        EntityOperations.executeAndGetCollection(ctx, path, Object.class);

        String url = transport.lastRequest.url();
        assertTrue(url.contains("$search="), "URL should contain $search param: " + url);
        assertTrue(url.contains("bike"), "URL should contain search term: " + url);
    }

    @Test
    void applyQueryParamInUrl() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(transport)
                .build();

        ContextPath path = ctx.basePath().addSegment("Products")
                .addQuery("$apply", "groupby((Category))/aggregate(Price with sum as Total)");
        EntityOperations.executeAndGetCollection(ctx, path, Object.class);

        String url = transport.lastRequest.url();
        assertTrue(url.contains("$apply="), "URL should contain $apply param: " + url);
        assertTrue(url.contains("groupby"), "URL should contain groupby: " + url);
        assertTrue(url.contains("aggregate"), "URL should contain aggregate: " + url);
    }
}
