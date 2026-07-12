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
    void executeCountThrowsOnNonNumericBody() {
        Context ctx = Context.builder()
                .baseUrl("https://example.com")
                .transport(stubTransport("not-a-number"))
                .build();

        ContextPath path = ctx.basePath().addSegment("People");
        assertThrows(ODataException.class, () -> EntityOperations.executeCount(ctx, path));
    }
}
