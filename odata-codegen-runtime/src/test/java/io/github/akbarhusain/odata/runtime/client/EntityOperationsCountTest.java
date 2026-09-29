package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class EntityOperationsCountTest {

    private HttpTransport stubTransport(String responseBody) {
        return new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return CompletableFuture.completedFuture(new HttpResponse(200,
                        Map.of("Content-Type", List.of("text/plain")),
                        responseBody.getBytes(StandardCharsets.UTF_8)));
            }

            @Override
            public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    static class CapturingTransport implements HttpTransport {
        HttpRequest lastRequest;
        String body;

        CapturingTransport(String body) {
            this.body = body;
        }

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.lastRequest = request;
            return CompletableFuture.completedFuture(new HttpResponse(200,
                    Map.of("Content-Type", List.of("text/plain")),
                    body.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void executeCountParsesPlainNumber() {
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(stubTransport("42"))
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        long count = EntityOperations.executeCount(ctx, path);
        assertEquals(42L, count);
    }

    @Test
    void executeCountAppendsCountSegment() {
        CapturingTransport transport = new CapturingTransport("5");
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(transport)
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        EntityOperations.executeCount(ctx, path);

        assertTrue(transport.lastRequest.url().endsWith("/People/$count"),
                "URL should end with /$count segment: " + transport.lastRequest.url());
    }

    @Test
    void executeCountPreservesFilterQuery() {
        CapturingTransport transport = new CapturingTransport("3");
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(transport)
                .build();

        ContextPath path = ctx.basePath().addSegment("People")
                .addQuery("$filter", "Age gt 25");
        EntityOperations.executeCount(ctx, path);

        String url = transport.lastRequest.url();
        assertTrue(url.contains("/People/$count"), "URL should contain /$count: " + url);
        assertTrue(url.contains("$filter=Age%20gt%2025"),
                "URL should preserve $filter: " + url);
        assertTrue(url.contains("/$count?$filter="),
                "$count segment must appear before query parameters: " + url);
    }

    @Test
    void executeCountRequestsTextPlainAccept() {
        // M2: the /$count endpoint returns plain text per the OData spec; the request
        // must not go out with the default application/json Accept.
        CapturingTransport transport = new CapturingTransport("42");
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(transport)
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        EntityOperations.executeCount(ctx, path);

        List<String> accept = transport.lastRequest.headers().get("Accept");
        assertNotNull(accept, "count request should carry an Accept header");
        assertEquals("text/plain", accept.get(0),
                "count request should ask for text/plain, not application/json");
    }

    @Test
    void executeCountDropsOptionsTheCountPathDoesNotAllow() {
        // OData 4.01 Part 2 §5.1.6: "Resource paths ending in /$count allow $filter and
        // $search." A nextLink-derived path carries the paging options the server put
        // there ($top/$skip/$skiptoken), so countValue() on a continued request would
        // otherwise emit /People/$count?$skiptoken=...&$top=... — a request a
        // conformant service rejects.
        CapturingTransport transport = new CapturingTransport("7");
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(transport)
                .build();

        ContextPath path = ctx.basePath().addSegment("People")
                .fromNextLink("https://example.com/People?$skiptoken=abc&$top=5&$filter=Age%20gt%2025");
        EntityOperations.executeCount(ctx, path);

        String url = transport.lastRequest.url();
        assertTrue(url.contains("/People/$count"), "URL should contain /$count: " + url);
        assertFalse(url.contains("$top"), "/$count must not carry $top: " + url);
        assertFalse(url.contains("$skiptoken"), "/$count must not carry a page token: " + url);
        assertTrue(url.contains("$filter=Age%20gt%2025"), "/$count must keep $filter: " + url);
    }

    @Test
    void executeCountKeepsSearch() {
        CapturingTransport transport = new CapturingTransport("7");
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(transport)
                .build();

        EntityOperations.executeCount(ctx, ctx.basePath().addSegment("People")
                .addQuery("$search", "blue OR green")
                .addQuery("$select", "Name"));
        String url = transport.lastRequest.url();
        assertTrue(url.contains("$search=blue%20OR%20green"), "/$count must keep $search: " + url);
        assertFalse(url.contains("$select"), "/$count must not carry $select: " + url);
    }

    @Test
    void executeCountKeepsCustomOptionsAndParameterAliases() {
        // The drop is deliberately narrow: only system options the count path disallows
        // plus continuation tokens. A custom option is the caller's explicit instruction,
        // and a retained $filter may reference a parameter alias -- dropping the alias
        // would leave a dangling reference, a worse URL than the one being fixed.
        CapturingTransport transport = new CapturingTransport("7");
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(transport)
                .build();

        EntityOperations.executeCount(ctx, ctx.basePath().addSegment("People")
                .addQuery("$filter", "contains(@w,Title)")
                .addQuery("@w", "'x'")
                .addQuery("tenant", "acme"));
        String url = transport.lastRequest.url();
        assertTrue(url.contains("%40w=") || url.contains("@w="),
                "a parameter alias must survive so the retained $filter still resolves: " + url);
        assertTrue(url.contains("tenant=acme"),
                "a custom option is the caller's instruction and must survive: " + url);
    }

    @Test
    void executeCountDropsContinuationToken() {
        CapturingTransport transport = new CapturingTransport("7");
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(transport)
                .build();

        EntityOperations.executeCount(ctx, ctx.basePath().addSegment("People")
                .addQuery("$skiptoken", "abc")
                .addQuery("$deltatoken", "d"));
        String url = transport.lastRequest.url();
        assertFalse(url.contains("skiptoken"), "a continuation token is meaningless off a page link: " + url);
        assertFalse(url.contains("deltatoken"), "a continuation token is meaningless off a page link: " + url);
    }

    @Test
    void executeCountThrowsOnNonNumericBody() {
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(stubTransport("not-a-number"))
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        assertThrows(ODataException.class, () -> EntityOperations.executeCount(ctx, path));
    }

    @Test
    void l4NullBodyFailsWithODataExceptionNotNpe() {
        // custom transports/interceptors can produce a null body (HttpResponse allows it)
        HttpTransport nullBody = new HttpTransport() {
            @Override
            public java.util.concurrent.CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return java.util.concurrent.CompletableFuture.completedFuture(new HttpResponse(200, java.util.Map.of(), null));
            }

            @Override
            public java.util.concurrent.CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        Context ctx = Context.builder().baseUrl("https://example.com").transport(nullBody).build();

        io.github.akbarhusain.odata.runtime.exception.ODataException ex = assertThrows(
                io.github.akbarhusain.odata.runtime.exception.ODataException.class,
                () -> EntityOperations.executeCount(ctx, ctx.basePath().addSegment("People")));
        assertTrue(ex.getMessage().contains("empty body"),
                "a diagnostic ODataException, not a bare NPE: " + ex.getMessage());
    }
}
